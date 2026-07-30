package com.sqmusicplus.v3.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.sqmusicplus.v3.config.AjaxResult;
import com.sqmusicplus.v3.lyric.LyricRepairJobCache;
import com.sqmusicplus.v3.lyric.LyricRepairService;
import com.sqmusicplus.v3.lyric.vo.LyricRepairRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/lyricRepair")
public class LyricRepairController {

    @Autowired
    private LyricRepairService lyricRepairService;

    @Autowired
    private LyricRepairJobCache lyricRepairJobCache;

    @Autowired
    @Qualifier("threadPoolTaskExecutor")
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    @SaCheckLogin
    @PostMapping("/preview")
    public AjaxResult preview(@RequestBody(required = false) LyricRepairRequest request) {
        LyricRepairRequest actualRequest = request != null ? request : new LyricRepairRequest();
        String jobId = UUID.randomUUID().toString();
        lyricRepairJobCache.create(jobId, true);
        try {
            threadPoolTaskExecutor.execute(() -> lyricRepairService.execute(jobId, actualRequest, true));
        } catch (RuntimeException e) {
            lyricRepairJobCache.error(jobId, e.getMessage() != null ? e.getMessage() : "任务提交失败");
            return AjaxResult.error("歌词扫描任务提交失败");
        }
        return AjaxResult.success("已提交歌词扫描任务", jobId);
    }

    @SaCheckLogin
    @PostMapping("/start")
    public AjaxResult start(@RequestBody(required = false) LyricRepairRequest request) {
        if (!lyricRepairService.reserveRepair()) {
            return AjaxResult.error("已有歌词补全任务正在执行");
        }

        LyricRepairRequest actualRequest = request != null ? request : new LyricRepairRequest();
        String jobId = UUID.randomUUID().toString();
        lyricRepairJobCache.create(jobId, false);
        try {
            threadPoolTaskExecutor.execute(() -> {
                try {
                    lyricRepairService.execute(jobId, actualRequest, false);
                } finally {
                    lyricRepairService.releaseRepair();
                }
            });
        } catch (RuntimeException e) {
            lyricRepairService.releaseRepair();
            lyricRepairJobCache.error(jobId, e.getMessage() != null ? e.getMessage() : "任务提交失败");
            return AjaxResult.error("歌词补全任务提交失败");
        }
        return AjaxResult.success("已提交歌词补全任务", jobId);
    }

    @SaCheckLogin
    @GetMapping("/status/{jobId}")
    public AjaxResult status(@PathVariable String jobId) {
        LyricRepairJobCache.LyricRepairJobStatus status = lyricRepairJobCache.get(jobId);
        if (status == null) {
            return AjaxResult.error("任务不存在或已过期");
        }
        return AjaxResult.success(status);
    }
}
