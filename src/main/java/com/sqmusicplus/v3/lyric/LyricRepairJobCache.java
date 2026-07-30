package com.sqmusicplus.v3.lyric;

import lombok.Data;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class LyricRepairJobCache {

    private static final int MAX_DETAILS = 500;

    private final ConcurrentHashMap<String, LyricRepairJobStatus> jobs = new ConcurrentHashMap<>();

    public void create(String jobId, boolean preview) {
        LyricRepairJobStatus status = new LyricRepairJobStatus(jobId);
        long now = System.currentTimeMillis();
        status.setPreview(preview);
        status.setStartedAt(now);
        status.setLastUpdatedAt(now);
        jobs.put(jobId, status);
    }

    public LyricRepairJobStatus get(String jobId) {
        return jobs.get(jobId);
    }

    public void update(String jobId, Consumer<LyricRepairJobStatus> updater) {
        LyricRepairJobStatus status = jobs.get(jobId);
        if (status == null) {
            return;
        }
        synchronized (status) {
            updater.accept(status);
            status.setLastUpdatedAt(System.currentTimeMillis());
        }
    }

    public void addDetail(String jobId, LyricRepairItem item) {
        update(jobId, status -> {
            if (status.getItems().size() < MAX_DETAILS) {
                status.getItems().add(item);
            } else {
                status.setDetailsTruncated(true);
            }
        });
    }

    public void done(String jobId, String message) {
        update(jobId, status -> {
            status.setPhase("done");
            status.setCurrent(status.getTotal());
            status.setMessage(message);
            status.setFinishedAt(System.currentTimeMillis());
        });
    }

    public void error(String jobId, String errorMessage) {
        update(jobId, status -> {
            status.setPhase("error");
            status.setMessage("歌词补全任务失败：" + errorMessage);
            status.setErrorMsg(errorMessage);
            status.setFinishedAt(System.currentTimeMillis());
        });
    }

    @Scheduled(fixedDelay = 120_000)
    public void cleanup() {
        long cutoff = System.currentTimeMillis() - 60 * 60_000L;
        jobs.entrySet().removeIf(entry -> {
            LyricRepairJobStatus status = entry.getValue();
            return status.getFinishedAt() > 0 && status.getFinishedAt() < cutoff;
        });
    }

    @Data
    public static class LyricRepairJobStatus {
        private final String jobId;
        private boolean preview;
        private String phase = "pending";
        private int current;
        private int total;
        private int scanned;
        private int candidates;
        private int skippedExisting;
        private int matched;
        private int repaired;
        private int unmatched;
        private int noLyric;
        private int failed;
        private String message = "等待开始";
        private String errorMsg;
        private String downloadPath;
        private long startedAt;
        private long lastUpdatedAt;
        private long finishedAt;
        private boolean detailsTruncated;
        private List<LyricRepairItem> items = new CopyOnWriteArrayList<>();

        public int getPercent() {
            if (total <= 0) {
                return "done".equals(phase) ? 100 : 0;
            }
            return Math.min(100, current * 100 / total);
        }
    }

    @Data
    public static class LyricRepairItem {
        private String file;
        private String status;
        private String message;
        private String plugName;
        private String musicId;
        private String musicName;
        private String artistName;
    }
}
