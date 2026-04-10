package com.sqmusicplus.v3.download;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 下载进度缓存（内存，不持久化）
 * key = DownloadInfo.id
 */
@Component
public class DownloadProgressCache {

    private final ConcurrentHashMap<Integer, TaskProgress> progressMap = new ConcurrentHashMap<>();

    public void update(Integer taskId, int percent, long bytesRead, long totalBytes) {
        if (taskId == null)
            return;
        progressMap.put(taskId, new TaskProgress(percent, bytesRead, totalBytes, System.currentTimeMillis()));
    }

    public void remove(Integer taskId) {
        if (taskId == null)
            return;
        progressMap.remove(taskId);
    }

    public Map<Integer, TaskProgress> getAll() {
        return Collections.unmodifiableMap(progressMap);
    }

    @Data
    public static class TaskProgress {
        private final int percent;
        private final long bytesRead;
        private final long totalBytes;
        private final long lastUpdateMs;

        /** 超过 stallThresholdMs 毫秒没有新进度则认为停滞 */
        public boolean isStalled(long stallThresholdMs) {
            return System.currentTimeMillis() - lastUpdateMs > stallThresholdMs;
        }
    }
}
