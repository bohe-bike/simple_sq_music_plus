package com.sqmusicplus.v3.lyric;

import com.sqmusicplus.v3.base.entity.DownloadInfo;
import com.sqmusicplus.v3.base.enums.SetConfigEnum;
import com.sqmusicplus.v3.base.service.DownloadInfoService;
import com.sqmusicplus.v3.config.SqConfigCache;
import com.sqmusicplus.v3.download.DownloadStatus;
import com.sqmusicplus.v3.lyric.vo.LyricRepairRequest;
import com.sqmusicplus.v3.plug.base.hander.SearchHanderAbstract;
import com.sqmusicplus.v3.plug.entity.PlugSearchMusicResult;
import com.sqmusicplus.v3.plug.entity.PlugSearchResult;
import com.sqmusicplus.v3.plug.entity.SearchKeyData;
import com.sqmusicplus.v3.utils.MusicUtils;
import com.sqmusicplus.v3.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Service
@Slf4j
public class LyricRepairService {

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "flac", "ape", "m4a", "aac", "ogg", "wav", "wma");
    private static final List<LyricSource> CROSS_SOURCE_ORDER = List.of(
            new LyricSource("kw", "酷我", SetConfigEnum.PLUG_KW_OPEN),
            new LyricSource("netease", "网易云", SetConfigEnum.PLUG_NETEASE_OPEN),
            new LyricSource("qqvip", "QQ", SetConfigEnum.PLUG_QQVIP_OPEN),
            new LyricSource("kg", "酷狗", SetConfigEnum.PLUG_KG_OPEN),
            new LyricSource("apple", "Apple Music", SetConfigEnum.PLUG_APPLE_OPEN));

    private final AtomicBoolean repairRunning = new AtomicBoolean(false);

    @Autowired
    private DownloadInfoService downloadInfoService;

    @Autowired
    private List<SearchHanderAbstract> searchHanderAbstractList;

    @Autowired
    private LyricRepairJobCache jobCache;

    public boolean reserveRepair() {
        return repairRunning.compareAndSet(false, true);
    }

    public void releaseRepair() {
        repairRunning.set(false);
    }

    public void execute(String jobId, LyricRepairRequest request, boolean previewOnly) {
        boolean overwriteExisting = request != null && Boolean.TRUE.equals(request.getOverwriteExisting());
        boolean crossSourceSearch = request == null || !Boolean.FALSE.equals(request.getCrossSourceSearch());
        try {
            Path root = resolveDownloadRoot();
            jobCache.update(jobId, status -> {
                status.setPhase("scanning");
                status.setDownloadPath(root.toString());
                status.setMessage("正在扫描下载目录");
            });

            List<Path> audioFiles = scanAudioFiles(root);
            List<Path> candidates = new ArrayList<>();
            int skippedExisting = 0;
            for (Path audioFile : audioFiles) {
                Path lyricFile = sidecarPath(audioFile);
                if (!overwriteExisting && Files.isRegularFile(lyricFile) && Files.size(lyricFile) > 0) {
                    skippedExisting++;
                } else {
                    candidates.add(audioFile);
                }
            }

            DownloadRecordIndex recordIndex = new DownloadRecordIndex(downloadInfoService.list());
            int finalSkippedExisting = skippedExisting;
            jobCache.update(jobId, status -> {
                status.setScanned(audioFiles.size());
                status.setCandidates(candidates.size());
                status.setSkippedExisting(finalSkippedExisting);
                status.setTotal(candidates.size());
                status.setPhase(previewOnly ? "matching" : "repairing");
                status.setMessage(previewOnly ? "正在匹配历史下载记录" : "正在补充歌词");
            });

            Map<String, LyricLookupResult> lyricCache = new HashMap<>();
            for (int index = 0; index < candidates.size(); index++) {
                Path audioFile = candidates.get(index);
                MatchResult match = recordIndex.match(audioFile, root);
                int current = index + 1;
                jobCache.update(jobId, status -> status.setCurrent(current));

                if (!match.isMatched()) {
                    jobCache.update(jobId, status -> status.setUnmatched(status.getUnmatched() + 1));
                    jobCache.addDetail(jobId, buildItem(root, audioFile, "unmatched", match.getMessage(), null));
                    continue;
                }

                DownloadInfo record = match.getRecord();
                jobCache.update(jobId, status -> status.setMatched(status.getMatched() + 1));
                if (previewOnly) {
                    jobCache.addDetail(jobId, buildItem(root, audioFile, "matched", "已匹配历史下载记录", record));
                    continue;
                }

                repairOne(jobId, root, audioFile, record, crossSourceSearch, lyricCache);
            }

            if (previewOnly) {
                jobCache.done(jobId, "扫描完成：待补充 " + candidates.size() + " 个，已匹配 "
                        + jobCache.get(jobId).getMatched() + " 个");
            } else {
                LyricRepairJobCache.LyricRepairJobStatus status = jobCache.get(jobId);
                jobCache.done(jobId, "歌词补全完成：成功 " + status.getRepaired() + " 个，无歌词 "
                        + status.getNoLyric() + " 个，失败 " + status.getFailed() + " 个");
            }
        } catch (Exception e) {
            log.error("歌词补全任务失败 jobId={}", jobId, e);
            jobCache.error(jobId, e.getMessage() != null ? e.getMessage() : "未知错误");
        }
    }

    private void repairOne(String jobId, Path root, Path audioFile, DownloadInfo record,
            boolean crossSourceSearch, Map<String, LyricLookupResult> lyricCache) {
        try {
            List<SearchHanderAbstract> fallbackHandlers = crossSourceSearch
                    ? enabledFallbackHandlers(record.getDownloadPlugName())
                    : List.of();
            LyricLookupResult lookup = lookupLyrics(record, crossSourceSearch, lyricCache, fallbackHandlers);
            if (!lookup.hasLyric()) {
                jobCache.update(jobId, status -> status.setNoLyric(status.getNoLyric() + 1));
                String message = crossSourceSearch ? "所有可用音源均未找到匹配歌词" : "原音源未返回歌词";
                jobCache.addDetail(jobId, buildItem(root, audioFile, "no_lyric", message, record));
                return;
            }

            writeLyricFile(sidecarPath(audioFile), lookup.lyric());
            jobCache.update(jobId, status -> {
                status.setRepaired(status.getRepaired() + 1);
                if (lookup.crossSource()) {
                    status.setCrossSourceRepaired(status.getCrossSourceRepaired() + 1);
                }
            });
            String message = lookup.crossSource()
                    ? "已从" + sourceLabel(lookup.plugName()) + "补全歌词"
                    : "歌词已从原音源写入";
            jobCache.addDetail(jobId, buildItem(root, audioFile, "repaired", message, record,
                    lookup.plugName(), lookup.musicId()));
        } catch (Exception e) {
            log.warn("补充歌词失败 file={}, plug={}, musicId={}", audioFile,
                    record.getDownloadPlugName(), record.getDownloadMusicId(), e);
            jobCache.update(jobId, status -> status.setFailed(status.getFailed() + 1));
            String message = e.getMessage() != null ? e.getMessage() : "未知错误";
            jobCache.addDetail(jobId, buildItem(root, audioFile, "failed", message, record));
        }
    }

    LyricLookupResult lookupLyrics(DownloadInfo record, boolean crossSourceSearch,
            Map<String, LyricLookupResult> lyricCache, List<SearchHanderAbstract> fallbackHandlers) {
        try {
            SearchHanderAbstract originalHandler = MusicUtils.getPlugHander(record.getDownloadPlugName(),
                    searchHanderAbstractList);
            String lyric = originalHandler.queryLyric(record.getDownloadMusicId());
            if (StringUtils.isNotBlank(lyric)) {
                return LyricLookupResult.found(lyric, originalHandler.getPlugName(),
                        record.getDownloadMusicId(), false);
            }
        } catch (Exception e) {
            log.warn("原音源歌词查询失败 plug={}, musicId={}", record.getDownloadPlugName(),
                    record.getDownloadMusicId(), e);
        }

        if (!crossSourceSearch || fallbackHandlers.isEmpty()) {
            return LyricLookupResult.notFound();
        }

        String cacheKey = lyricCacheKey(record);
        LyricLookupResult cached = lyricCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        LyricLookupResult result = searchCrossSources(record, fallbackHandlers);
        lyricCache.put(cacheKey, result);
        return result;
    }

    LyricLookupResult searchCrossSources(DownloadInfo record, List<SearchHanderAbstract> handlers) {
        String firstArtist = firstArtist(record.getDownloadArtistname());
        String keyword = record.getDownloadMusicname();
        if (StringUtils.isNotBlank(firstArtist)) {
            keyword += " " + firstArtist;
        }
        SearchKeyData searchKeyData = new SearchKeyData()
                .setSearchkey(keyword)
                .setPageIndex(1)
                .setPageSize(20);

        for (SearchHanderAbstract handler : handlers) {
            try {
                searchKeyData.setPlugName(handler.getPlugName());
                PlugSearchResult<PlugSearchMusicResult> searchResult = handler.querySongByName(searchKeyData);
                PlugSearchMusicResult candidate = selectLyricCandidate(record,
                        searchResult != null ? searchResult.getRecords() : null);
                if (candidate == null) {
                    continue;
                }
                String lyric = candidate.getLyric();
                String lyricMusicId = StringUtils.isNotBlank(candidate.getId())
                        ? candidate.getId()
                        : candidate.getLyricId();
                if (StringUtils.isBlank(lyric) && StringUtils.isNotBlank(lyricMusicId)) {
                    lyric = handler.queryLyric(lyricMusicId);
                }
                if (StringUtils.isNotBlank(lyric)) {
                    return LyricLookupResult.found(lyric, handler.getPlugName(), lyricMusicId, true);
                }
            } catch (Exception e) {
                log.warn("跨源歌词查询失败 plug={}, music={}", handler.getPlugName(),
                        record.getDownloadMusicname(), e);
            }
        }
        return LyricLookupResult.notFound();
    }

    private List<SearchHanderAbstract> enabledFallbackHandlers(String originalPlugName) {
        return selectFallbackHandlers(originalPlugName, searchHanderAbstractList,
                plugName -> CROSS_SOURCE_ORDER.stream()
                        .filter(source -> source.plugName().equals(plugName))
                        .anyMatch(source -> isPluginEnabled(source.config())));
    }

    static List<SearchHanderAbstract> selectFallbackHandlers(String originalPlugName,
            List<SearchHanderAbstract> availableHandlers, Predicate<String> isEnabled) {
        Map<String, SearchHanderAbstract> handlersByName = new HashMap<>();
        for (SearchHanderAbstract handler : availableHandlers) {
            handlersByName.put(handler.getPlugName(), handler);
        }
        String originalGroup = sourceGroup(originalPlugName);
        List<SearchHanderAbstract> enabledHandlers = new ArrayList<>();
        for (LyricSource source : CROSS_SOURCE_ORDER) {
            if (sourceGroup(source.plugName()).equals(originalGroup) || !isEnabled.test(source.plugName())) {
                continue;
            }
            SearchHanderAbstract handler = handlersByName.get(source.plugName());
            if (handler != null) {
                enabledHandlers.add(handler);
            }
        }
        return enabledHandlers;
    }

    private boolean isPluginEnabled(SetConfigEnum config) {
        return Boolean.parseBoolean(SqConfigCache.getSqConfigValue(config));
    }

    static PlugSearchMusicResult selectLyricCandidate(DownloadInfo record,
            List<PlugSearchMusicResult> candidates) {
        if (candidates == null || candidates.isEmpty()
                || StringUtils.isBlank(record.getDownloadArtistname())) {
            return null;
        }
        Set<String> expectedArtists = normalizedArtists(record.getDownloadArtistname());
        String expectedTitle = normalize(record.getDownloadMusicname());
        String expectedAlbum = normalize(record.getDownloadAlbumname());
        Map<String, ScoredCandidate> matched = new LinkedHashMap<>();
        for (PlugSearchMusicResult candidate : candidates) {
            if (candidate == null || StringUtils.isBlank(candidate.getId())
                    || !expectedTitle.equals(normalize(candidate.getName()))
                    || !artistMatches(expectedArtists, candidate.getArtistName())) {
                continue;
            }
            int score = 100;
            if (StringUtils.isNotBlank(expectedAlbum)
                    && expectedAlbum.equals(normalize(candidate.getAlbumName()))) {
                score += 20;
            }
            String key = normalize(candidate.getPlugName()) + ":" + candidate.getId();
            ScoredCandidate current = new ScoredCandidate(candidate, score);
            ScoredCandidate existing = matched.get(key);
            if (existing == null || current.score() > existing.score()) {
                matched.put(key, current);
            }
        }
        List<ScoredCandidate> ranked = matched.values().stream()
                .sorted(Comparator.comparingInt(ScoredCandidate::score).reversed())
                .toList();
        if (ranked.isEmpty()) {
            return null;
        }
        if (ranked.size() > 1 && ranked.get(0).score() == ranked.get(1).score()) {
            return null;
        }
        return ranked.get(0).candidate();
    }

    private static boolean artistMatches(Set<String> expectedArtists, List<String> candidateArtists) {
        if (candidateArtists == null || candidateArtists.isEmpty()) {
            return false;
        }
        return candidateArtists.stream()
                .flatMap(artist -> normalizedArtists(artist).stream())
                .anyMatch(expectedArtists::contains);
    }

    private static Set<String> normalizedArtists(String artists) {
        if (artists == null) {
            return Set.of();
        }
        return Stream.of(artists.split("[/&;,，、]"))
                .map(LyricRepairService::normalize)
                .filter(StringUtils::isNotBlank)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String lyricCacheKey(DownloadInfo record) {
        return normalize(record.getDownloadMusicname()) + "|"
                + normalize(record.getDownloadArtistname()) + "|"
                + normalize(record.getDownloadAlbumname());
    }

    private static String sourceGroup(String plugName) {
        return "qq".equals(plugName) || "qqvip".equals(plugName) ? "qq" : plugName;
    }

    private static String sourceLabel(String plugName) {
        return CROSS_SOURCE_ORDER.stream()
                .filter(source -> source.plugName().equals(plugName))
                .map(LyricSource::label)
                .findFirst()
                .orElse(plugName);
    }

    private Path resolveDownloadRoot() throws IOException {
        String configuredPath = SqConfigCache.getSqConfigValue(SetConfigEnum.SYSTEM_DOWNLOAD_PATH);
        if (StringUtils.isBlank(configuredPath)) {
            throw new IOException("未配置音乐下载目录");
        }
        Path root = Path.of(configuredPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("音乐下载目录不存在：" + root);
        }
        return root.toRealPath();
    }

    static List<Path> scanAudioFiles(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(LyricRepairService::isAudioFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    static boolean isAudioFile(Path path) {
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }
        return AUDIO_EXTENSIONS.contains(fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT));
    }

    static Path sidecarPath(Path audioFile) {
        String fileName = audioFile.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        return audioFile.resolveSibling(baseName + ".lrc");
    }

    static void writeLyricFile(Path lyricFile, String lyric) throws IOException {
        Files.createDirectories(lyricFile.getParent());
        Path tempFile = lyricFile.resolveSibling(lyricFile.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(tempFile, lyric, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, lyricFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, lyricFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private LyricRepairJobCache.LyricRepairItem buildItem(Path root, Path audioFile, String status,
            String message, DownloadInfo record) {
        return buildItem(root, audioFile, status, message, record, null, null);
    }

    private LyricRepairJobCache.LyricRepairItem buildItem(Path root, Path audioFile, String status,
            String message, DownloadInfo record, String actualPlugName, String actualMusicId) {
        LyricRepairJobCache.LyricRepairItem item = new LyricRepairJobCache.LyricRepairItem();
        item.setFile(root.relativize(audioFile).toString());
        item.setStatus(status);
        item.setMessage(message);
        if (record != null) {
            item.setPlugName(StringUtils.isNotBlank(actualPlugName) ? actualPlugName : record.getDownloadPlugName());
            item.setMusicId(StringUtils.isNotBlank(actualMusicId) ? actualMusicId : record.getDownloadMusicId());
            item.setMusicName(record.getDownloadMusicname());
            item.setArtistName(record.getDownloadArtistname());
        }
        return item;
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    static String baseName(Path path) {
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    static String titlePart(String fileBaseName) {
        int separatorIndex = fileBaseName.lastIndexOf(" - ");
        return separatorIndex > 0 ? fileBaseName.substring(0, separatorIndex) : fileBaseName;
    }

    private static String firstArtist(String artists) {
        if (artists == null) {
            return "";
        }
        return Stream.of(artists.split("[/&;,，、]"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("");
    }

    static final class DownloadRecordIndex {
        private final Map<String, List<DownloadInfo>> byFileName = new HashMap<>();
        private final Map<String, List<DownloadInfo>> byTitle = new HashMap<>();

        DownloadRecordIndex(Collection<DownloadInfo> records) {
            if (records == null) {
                return;
            }
            for (DownloadInfo record : records) {
                if (!isUsable(record)) {
                    continue;
                }
                add(byFileName, normalize(record.getDownloadFile()), record);
                add(byFileName, normalize(record.getDownloadMusicname() + " - "
                        + record.getDownloadArtistname()), record);
                add(byTitle, normalize(record.getDownloadMusicname()), record);
            }
        }

        MatchResult match(Path audioFile, Path root) {
            String fileBaseName = baseName(audioFile);
            String fileKey = normalize(fileBaseName);
            String titleKey = normalize(titlePart(fileBaseName));
            LinkedHashMap<String, DownloadInfo> candidates = new LinkedHashMap<>();
            addCandidates(candidates, byFileName.get(fileKey));
            addCandidates(candidates, byTitle.get(fileKey));
            addCandidates(candidates, byTitle.get(titleKey));

            if (candidates.isEmpty()) {
                return MatchResult.unmatched("未找到对应的历史下载记录");
            }

            List<ScoredRecord> scoredRecords = candidates.values().stream()
                    .map(record -> new ScoredRecord(record, score(record, audioFile, root, fileKey, titleKey)))
                    .sorted(Comparator.comparingInt(ScoredRecord::score).reversed())
                    .toList();
            ScoredRecord best = scoredRecords.get(0);
            if (best.score() < 60) {
                return MatchResult.unmatched("历史记录匹配度不足");
            }
            if (scoredRecords.size() > 1 && scoredRecords.get(1).score() == best.score()) {
                return MatchResult.unmatched("存在多个同等匹配的历史下载记录");
            }
            return MatchResult.matched(best.record());
        }

        private static int score(DownloadInfo record, Path audioFile, Path root, String fileKey, String titleKey) {
            int score = 0;
            if (fileKey.equals(normalize(record.getDownloadFile()))
                    || fileKey.equals(normalize(record.getDownloadMusicname() + " - "
                            + record.getDownloadArtistname()))) {
                score += 100;
            } else if (fileKey.equals(normalize(record.getDownloadMusicname()))) {
                score += 70;
            } else if (titleKey.equals(normalize(record.getDownloadMusicname()))) {
                score += 65;
            }

            Path parent = audioFile.getParent();
            if (parent != null && normalize(parent.getFileName().toString())
                    .equals(normalize(record.getDownloadAlbumname()))) {
                score += 20;
            }
            Path artistDirectory = parent != null ? parent.getParent() : null;
            if (artistDirectory != null && artistDirectory.startsWith(root)
                    && normalize(artistDirectory.getFileName().toString())
                    .equals(normalize(firstArtist(record.getDownloadArtistname())))) {
                score += 15;
            }
            if (DownloadStatus.success.getValue().equals(record.getDownloadStatus())) {
                score += 10;
            }
            return score;
        }

        private static boolean isUsable(DownloadInfo record) {
            return record != null
                    && StringUtils.isNotBlank(record.getDownloadMusicId())
                    && StringUtils.isNotBlank(record.getDownloadPlugName())
                    && StringUtils.isNotBlank(record.getDownloadMusicname());
        }

        private static void add(Map<String, List<DownloadInfo>> index, String key, DownloadInfo record) {
            if (StringUtils.isBlank(key)) {
                return;
            }
            index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(record);
        }

        private static void addCandidates(Map<String, DownloadInfo> target, List<DownloadInfo> records) {
            if (records == null) {
                return;
            }
            for (DownloadInfo record : records) {
                String key = record.getDownloadPlugName() + ":" + record.getDownloadMusicId();
                target.putIfAbsent(key, record);
            }
        }

        private static String firstArtist(String artists) {
            if (artists == null) {
                return "";
            }
            int separatorIndex = artists.indexOf('&');
            return separatorIndex >= 0 ? artists.substring(0, separatorIndex) : artists;
        }
    }

    private record ScoredRecord(DownloadInfo record, int score) {
    }

    private record ScoredCandidate(PlugSearchMusicResult candidate, int score) {
    }

    private record LyricSource(String plugName, String label, SetConfigEnum config) {
    }

    record LyricLookupResult(String lyric, String plugName, String musicId, boolean crossSource) {
        static LyricLookupResult found(String lyric, String plugName, String musicId, boolean crossSource) {
            return new LyricLookupResult(lyric, plugName, musicId, crossSource);
        }

        static LyricLookupResult notFound() {
            return new LyricLookupResult(null, null, null, false);
        }

        boolean hasLyric() {
            return StringUtils.isNotBlank(lyric);
        }
    }

    static final class MatchResult {
        private final DownloadInfo record;
        private final String message;

        private MatchResult(DownloadInfo record, String message) {
            this.record = record;
            this.message = message;
        }

        static MatchResult matched(DownloadInfo record) {
            return new MatchResult(record, null);
        }

        static MatchResult unmatched(String message) {
            return new MatchResult(null, message);
        }

        boolean isMatched() {
            return record != null;
        }

        DownloadInfo getRecord() {
            return record;
        }

        String getMessage() {
            return message;
        }
    }
}
