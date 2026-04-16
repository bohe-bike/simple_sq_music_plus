package com.sqmusicplus.v3.download;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sqmusicplus.v3.base.entity.vo.ParserEntity;
import com.sqmusicplus.v3.plug.entity.Music;
import lombok.Data;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 歌单解析任务状态缓存（内存，不持久化）
 * key = jobId (UUID)
 */
@Component
public class ParseJobCache {

    private final ConcurrentHashMap<String, ParseJobStatus> jobMap = new ConcurrentHashMap<>();

    public void create(String jobId) {
        ParseJobStatus status = new ParseJobStatus(jobId);
        long now = System.currentTimeMillis();
        status.setStartedAt(now);
        status.setLastUpdatedAt(now);
        jobMap.put(jobId, status);
    }

    public void updateFetching(String jobId, int total) {
        updateStage(jobId, "fetching", 0, total, "正在获取歌单曲目…共 " + total + " 首");
    }

    public void updateMatching(String jobId, int current, int total) {
        updateStage(jobId, "matching", current, total, "跨源匹配中…" + current + " / " + total);
    }

    public void updateBuilding(String jobId, int current, int total) {
        ParseJobStatus s = jobMap.get(jobId);
        if (s == null)
            return;
        s.setPhase("building");
        s.setCurrent(current);
        s.setTotal(total);
        s.setMessage("构建任务中…" + current + " / " + total);
        touch(s);
    }

    public void updateStage(String jobId, String phase, int current, int total, String message) {
        ParseJobStatus s = jobMap.get(jobId);
        if (s == null)
            return;
        s.setPhase(phase);
        s.setCurrent(current);
        s.setTotal(total);
        s.setMessage(message);
        touch(s);
    }

    public void done(String jobId, int total) {
        ParseJobStatus s = jobMap.get(jobId);
        if (s == null)
            return;
        s.setPhase("done");
        s.setCurrent(total);
        s.setTotal(total);
        s.setMessage("已提交 " + total + " 首下载任务");
        s.setFinishedAt(System.currentTimeMillis());
    }

    public void previewDone(String jobId, List<Music> songs) {
        ParseJobStatus s = jobMap.get(jobId);
        if (s == null)
            return;
        // 按 plugName+id 去重，过滤平台 API 分页重叠或歌单内重复收录的曲目
        List<Music> deduped = deduplicateSongs(songs);
        int total = deduped.size();
        s.setPhase("done");
        s.setCurrent(total);
        s.setTotal(total);
        s.setRawSongs(deduped);
        s.setSongs(toPreviewSongs(deduped));
        s.setMessage("解析完成，共 " + total + " 首");
        touch(s);
        s.setFinishedAt(System.currentTimeMillis());
    }

    private List<Music> deduplicateSongs(List<Music> songs) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Music> result = new ArrayList<>();
        for (Music song : songs) {
            String id = song.getId();
            String key = (song.getPlugName() != null ? song.getPlugName() : "") + ":" + (id != null ? id : "");
            // id 为空时按歌曲名+歌手去重，避免丢弃正常曲目
            if (id == null || id.isEmpty()) {
                String name = song.getMusicName() != null ? song.getMusicName() : "";
                String artist = song.getMusicArtists() != null && !song.getMusicArtists().isEmpty()
                        ? song.getMusicArtists().get(0)
                        : "";
                key = name + "|" + artist;
            }
            if (seen.add(key)) {
                result.add(song);
            }
        }
        return result;
    }

    private List<Music> toPreviewSongs(List<Music> songs) {
        ArrayList<Music> previewSongs = new ArrayList<>(songs.size());
        for (Music song : songs) {
            Music previewSong = new Music();
            previewSong.setId(song.getId());
            previewSong.setMusicName(song.getMusicName());
            previewSong.setMusicArtists(song.getMusicArtists());
            previewSong.setMusicAlbum(song.getMusicAlbum());
            previewSong.setPlugName(song.getPlugName());
            previewSong.setAlbum(song.getAlbum());
            previewSong.setArtists(song.getArtists());
            previewSong.setBit(song.getBit());
            previewSong.setBits(song.getBits());
            previewSongs.add(previewSong);
        }
        return previewSongs;
    }

    public void textPreviewDone(String jobId, List<ParserEntity> entities) {
        ParseJobStatus s = jobMap.get(jobId);
        if (s == null)
            return;
        int total = entities.size();
        s.setPhase("done");
        s.setCurrent(total);
        s.setTotal(total);
        s.setParserEntities(entities);
        s.setMessage("识别完成，共 " + total + " 条");
        touch(s);
        s.setFinishedAt(System.currentTimeMillis());
    }

    public List<ParserEntity> getParserEntities(String jobId) {
        ParseJobStatus s = jobMap.get(jobId);
        if (s == null)
            return null;
        return s.getParserEntities();
    }

    public void error(String jobId, String errorMsg) {
        ParseJobStatus s = jobMap.get(jobId);
        if (s == null)
            return;
        s.setPhase("error");
        s.setErrorMsg(errorMsg);
        s.setMessage("解析失败：" + errorMsg);
        touch(s);
        s.setFinishedAt(System.currentTimeMillis());
    }

    private void touch(ParseJobStatus status) {
        long now = System.currentTimeMillis();
        if (status.getStartedAt() <= 0) {
            status.setStartedAt(now);
        }
        status.setLastUpdatedAt(now);
    }

    public ParseJobStatus get(String jobId) {
        return jobMap.get(jobId);
    }

    public List<Music> getRawSongs(String jobId) {
        ParseJobStatus s = jobMap.get(jobId);
        if (s == null) {
            return null;
        }
        return s.getRawSongs();
    }

    /** 定时清理超过 60 分钟的已完成/失败任务 */
    @Scheduled(fixedDelay = 120_000)
    public void cleanup() {
        long cutoff = System.currentTimeMillis() - 60 * 60_000L;
        jobMap.entrySet().removeIf(e -> {
            ParseJobStatus s = e.getValue();
            return s.getFinishedAt() > 0 && s.getFinishedAt() < cutoff;
        });
    }

    @Data
    public static class ParseJobStatus {
        private final String jobId;
        private String phase = "pending"; // pending / fetching / done / error
        private int current = 0;
        private int total = 0;
        private String message = "等待开始…";
        private String errorMsg;
        private long startedAt = 0;
        private long lastUpdatedAt = 0;
        private long finishedAt = 0;
        /** 预览完成后携带的曲目列表 */
        private List<Music> songs;
        /** 服务端保留的完整曲目列表，供下载时复用 */
        @JsonIgnore
        private List<Music> rawSongs;
        /** 文本歌单解析完成后的结果列表 */
        private List<ParserEntity> parserEntities;

        public int getPercent() {
            if (total <= 0)
                return 0;
            return Math.min(100, current * 100 / total);
        }
    }
}
