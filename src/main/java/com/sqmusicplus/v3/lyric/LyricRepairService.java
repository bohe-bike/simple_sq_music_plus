package com.sqmusicplus.v3.lyric;

import com.sqmusicplus.v3.base.entity.DownloadInfo;
import com.sqmusicplus.v3.base.enums.SetConfigEnum;
import com.sqmusicplus.v3.base.service.DownloadInfoService;
import com.sqmusicplus.v3.config.SqConfigCache;
import com.sqmusicplus.v3.download.DownloadStatus;
import com.sqmusicplus.v3.lyric.vo.LyricRepairRequest;
import com.sqmusicplus.v3.plug.base.hander.SearchHanderAbstract;
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
import java.util.stream.Stream;

@Service
@Slf4j
public class LyricRepairService {

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "flac", "ape", "m4a", "aac", "ogg", "wav", "wma");

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

                repairOne(jobId, root, audioFile, record);
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

    private void repairOne(String jobId, Path root, Path audioFile, DownloadInfo record) {
        try {
            SearchHanderAbstract handler = MusicUtils.getPlugHander(record.getDownloadPlugName(),
                    searchHanderAbstractList);
            String lyric = handler.queryLyric(record.getDownloadMusicId());
            if (StringUtils.isBlank(lyric)) {
                jobCache.update(jobId, status -> status.setNoLyric(status.getNoLyric() + 1));
                jobCache.addDetail(jobId, buildItem(root, audioFile, "no_lyric", "音源未返回歌词", record));
                return;
            }

            writeLyricFile(sidecarPath(audioFile), lyric);
            jobCache.update(jobId, status -> status.setRepaired(status.getRepaired() + 1));
            jobCache.addDetail(jobId, buildItem(root, audioFile, "repaired", "歌词已写入", record));
        } catch (Exception e) {
            log.warn("补充歌词失败 file={}, plug={}, musicId={}", audioFile,
                    record.getDownloadPlugName(), record.getDownloadMusicId(), e);
            jobCache.update(jobId, status -> status.setFailed(status.getFailed() + 1));
            String message = e.getMessage() != null ? e.getMessage() : "未知错误";
            jobCache.addDetail(jobId, buildItem(root, audioFile, "failed", message, record));
        }
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
        LyricRepairJobCache.LyricRepairItem item = new LyricRepairJobCache.LyricRepairItem();
        item.setFile(root.relativize(audioFile).toString());
        item.setStatus(status);
        item.setMessage(message);
        if (record != null) {
            item.setPlugName(record.getDownloadPlugName());
            item.setMusicId(record.getDownloadMusicId());
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
