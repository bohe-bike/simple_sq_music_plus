package com.sqmusicplus.v3.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sqmusicplus.v3.config.AjaxResult;
import com.sqmusicplus.v3.monitor.entity.SqMonitor;
import com.sqmusicplus.v3.monitor.service.SqMonitorService;
import com.sqmusicplus.v3.plug.netease.entity.PlaylistTrackAllResult;
import com.sqmusicplus.v3.plug.netease.hander.NeteaseHander;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * @Classname MonitorController
 * @Description
 * @Version 1.0.0
 * @Date 2026/3/2
 * @Created by SQ
 */
@Slf4j
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {
    @Autowired
    private SqMonitorService monitorService;
    @Autowired
    private NeteaseHander neteaseHander;

    @RequestMapping("/list")
    public AjaxResult list() {
        List<SqMonitor> list = monitorService.list();
        return AjaxResult.success(list);
    }

    @RequestMapping("/add")
    public AjaxResult add(@RequestBody SqMonitor sqMonitor) {
        sqMonitor.setCreateTime(new Date());
        sqMonitor.setUpdateTime(new Date());
        String plugName = sqMonitor.getPlugName();
        String targetId = sqMonitor.getTargetId();
        LambdaQueryWrapper<SqMonitor> sqMonitorLambdaQueryWrapper = new LambdaQueryWrapper<>();
        LambdaQueryWrapper<SqMonitor> wrapper = sqMonitorLambdaQueryWrapper.eq(SqMonitor::getPlugName, plugName)
                .eq(SqMonitor::getTargetId, targetId);
        long count = monitorService.count(wrapper);
        if (count > 0) {
            return AjaxResult.error("已存在,不要重复添加");
        }
        boolean save = monitorService.save(sqMonitor);
        return AjaxResult.success(save);
    }

    /**
     * 仅凭 URL 快速保存监听，元数据在后台异步补全
     */
    @RequestMapping("/addByUrl")
    public AjaxResult addByUrl(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        if (url == null || url.trim().isEmpty()) {
            return AjaxResult.error("URL 不能为空");
        }
        url = url.trim();

        // 本地解析，不调外网
        String plugName = null;
        String targetId = null;
        String type = null;
        if (url.contains("music.163.com")) {
            plugName = neteaseHander.getPlugName();
            if (url.contains("playlist")) {
                type = "playlist";
                targetId = getUrlParam(url, "id");
            }
        }

        if (plugName == null || targetId == null || type == null) {
            return AjaxResult.error("目前仅支持网易云歌单，需要其他的请 issues 留言！");
        }

        // 重复检查
        long count = monitorService.count(new LambdaQueryWrapper<SqMonitor>()
                .eq(SqMonitor::getPlugName, plugName)
                .eq(SqMonitor::getTargetId, targetId));
        if (count > 0) {
            return AjaxResult.error("已存在，不要重复添加");
        }

        // 立即入库
        SqMonitor sqMonitor = new SqMonitor();
        sqMonitor.setPlugName(plugName);
        sqMonitor.setTargetId(targetId);
        sqMonitor.setType(type);
        sqMonitor.setTargetUrl(url);
        sqMonitor.setEnabled("1");
        sqMonitor.setCreateTime(new Date());
        sqMonitor.setUpdateTime(new Date());
        monitorService.save(sqMonitor);

        // 后台异步补全元数据
        final String finalTargetId = targetId;
        final Integer savedId = sqMonitor.getId();
        CompletableFuture.runAsync(() -> {
            try {
                PlaylistTrackAllResult playListInfo = neteaseHander.getPlayListInfo(finalTargetId);
                PlaylistTrackAllResult.playlist playlist = playListInfo.getPlaylist();
                SqMonitor update = new SqMonitor();
                update.setId(savedId);
                update.setTargetName(playlist.getName());
                update.setTargetCount(playlist.getTrackCount());
                update.setTargetDesc(playlist.getDescription());
                update.setTargetCover(playlist.getCoverImgUrl());
                update.setUpdateTime(new Date());
                monitorService.updateById(update);
                log.info("监听元数据补全成功: id={}, name={}", savedId, playlist.getName());
            } catch (Exception e) {
                log.error("监听元数据补全失败: id={}", savedId, e);
            }
        });

        return AjaxResult.success("监听已创建，元数据正在后台补全");
    }

    @RequestMapping("/delete")
    public AjaxResult delete(@RequestBody SqMonitor sqMonitor) {
        boolean delete = monitorService.removeById(sqMonitor.getId());
        return AjaxResult.success(delete);
    }

    private String getUrlParam(String url, String paramName) {
        try {
            URL parsed = new URL(url);
            String query = parsed.getQuery();
            if (query == null)
                return null;
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && URLDecoder.decode(kv[0], "UTF-8").equals(paramName)) {
                    return URLDecoder.decode(kv[1], "UTF-8");
                }
            }
        } catch (MalformedURLException | UnsupportedEncodingException e) {
            log.warn("URL 解析失败: {}", url, e);
        }
        return null;
    }

}
