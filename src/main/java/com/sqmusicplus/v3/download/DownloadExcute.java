package com.sqmusicplus.v3.download;

import cn.hutool.core.util.ReflectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sqmusicplus.v3.base.entity.DownloadInfo;
import com.sqmusicplus.v3.base.enums.SetConfigEnum;
import com.sqmusicplus.v3.base.service.DownloadInfoService;
import com.sqmusicplus.v3.config.SqConfigCache;
import com.sqmusicplus.v3.config.exception.IgnoreDownloadException;
import com.sqmusicplus.v3.plug.base.hander.SearchHander;
import com.sqmusicplus.v3.plug.base.hander.SearchHanderAbstract;
import com.sqmusicplus.v3.plug.entity.PlugSearchMusicResult;
import com.sqmusicplus.v3.plug.entity.PlugSearchResult;
import com.sqmusicplus.v3.plug.entity.SearchKeyData;
import com.sqmusicplus.v3.utils.MusicUtils;
import com.sqmusicplus.v3.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * @Classname DownloadExcute
 * @Description 下载执行器
 * @Version 1.0.0
 * @Date 2023/8/23 14:31
 * @Created by SQ
 */

@Slf4j
@Service
@Lazy
public class DownloadExcute {

    @Autowired
    private DownloadInfoService downloadInfoService;
    @Autowired
    @Qualifier("threadPoolTaskExecutor")
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;
    @Autowired
    private List<SearchHanderAbstract> searchHanderAbstractList;
    @Autowired
    private DownloadProgressCache downloadProgressCache;

    public void getDownloadInfo() {

        LambdaQueryWrapper<DownloadInfo> objectLambdaQueryWrapper = new LambdaQueryWrapper<>();
        objectLambdaQueryWrapper.eq(DownloadInfo::getDownloadStatus, DownloadStatus.waiting.value);
        long waitsize = downloadInfoService.count(objectLambdaQueryWrapper);

        List<DownloadInfo> records = null;
        if (waitsize > 0) {
            String init_download = SqConfigCache.getSqConfigValue(SetConfigEnum.SYSTEM_DOWNLOAD_NUM);
            Long downloadsize = Long.valueOf(init_download);
            LambdaQueryWrapper<DownloadInfo> downloadInfoQueryWrapper = new LambdaQueryWrapper<>();
            downloadInfoQueryWrapper.eq(DownloadInfo::getDownloadStatus, DownloadStatus.loading.value);
            long count = downloadInfoService.count(downloadInfoQueryWrapper);
            log.debug("正在下载任务--->{}个", count);
            if (count - downloadsize < 0) {
                long l = downloadsize - count;
                Page<DownloadInfo> page = downloadInfoService.page(new Page<>(0, l), objectLambdaQueryWrapper);
                records = page.getRecords();
                log.debug("本次补充--->{}个", records.size());
            }
        }
        if (records != null && records.size() > 0) {
            for (DownloadInfo record : records) {
                threadPoolTaskExecutor.execute(() -> {
                    try {
                        record.setDownloadStatus(DownloadStatus.loading.getValue());
                        downloadInfoService.updateById(record);
                        log.debug("修改进行中状态--->{}", record);
                        // DownloadEntity downloadEntity =
                        // MusicUtils.downloadInfoToDownloadEntity(record);
                        Object bean = SpringContextUtil.getBean(record.getSpringName());
                        if (bean instanceof SearchHander) {
                            SearchHander searchHander = (SearchHander) bean;
                            try {
                                searchHander.dnonloadAndSaveToFile(record, searchHander);
                                // 捕获内容
                                record.setDownloadStatus(DownloadStatus.success.getValue());
                                downloadInfoService.updateById(record);
                                log.debug("修改完成状态--->{}", record);
                            } catch (IgnoreDownloadException e) {
                                // 一般是酷我的歌曲信息获取失败导致的需要从新下载
                                record.setDownloadStatus(DownloadStatus.waiting.getValue());
                                record.setDownloadMsg(e.getMessage());
                                downloadInfoService.updateById(record);
                                return;
                            } catch (Exception e) {
                                log.warn("主源下载失败 {}: {}，尝试跨源匹配...", record.getDownloadMusicname(), e.getMessage());
                                DownloadInfo fallback = tryFallback(record);
                                if (fallback != null) {
                                    try {
                                        Object altBean = SpringContextUtil.getBean(fallback.getSpringName());
                                        if (altBean instanceof SearchHander) {
                                            ((SearchHander) altBean).dnonloadAndSaveToFile(fallback,
                                                    (SearchHander) altBean);
                                            record.setDownloadStatus(DownloadStatus.success.getValue());
                                            record.setDownloadMsg("跨源完成: " + fallback.getDownloadPlugName());
                                            record.setDownloadPlugName(fallback.getDownloadPlugName());
                                            downloadInfoService.updateById(record);
                                            log.info("跨源下载成功 {}", record.getDownloadMusicname());
                                            return;
                                        }
                                    } catch (Exception fallbackEx) {
                                        log.error("跨源下载也失败 {}: {}", record.getDownloadMusicname(),
                                                fallbackEx.getMessage());
                                    }
                                }
                                e.printStackTrace();
                                record.setDownloadStatus(DownloadStatus.error.getValue());
                                record.setDownloadMsg(e.getMessage());
                                downloadInfoService.updateById(record);
                                log.debug("修改错误状态--->{}", record);
                            } finally {
                                downloadProgressCache.remove(record.getId());
                            }
                        } else {
                            try {
                                ReflectUtil.invoke(bean, "dnonloadAndSaveToFile", record, bean);
                                record.setDownloadStatus(DownloadStatus.success.getValue());
                                downloadInfoService.updateById(record);
                                log.debug("修改完成状态--->{}", record);
                            } catch (IgnoreDownloadException e) {
                                // 一般是酷我的歌曲信息获取失败导致的需要从新下载
                                record.setDownloadStatus(DownloadStatus.waiting.getValue());
                                record.setDownloadMsg(e.getMessage());
                                downloadInfoService.updateById(record);
                                return;
                            } catch (Exception e) {
                                e.printStackTrace();
                                record.setDownloadStatus(DownloadStatus.error.getValue());
                                record.setDownloadMsg(e.getMessage());
                                downloadInfoService.updateById(record);
                                log.debug("修改错误状态--->{}", record);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        record.setDownloadStatus(DownloadStatus.error.getValue());
                        record.setDownloadMsg(e.getMessage());
                        downloadInfoService.updateById(record);
                        log.debug("修改错误状态--->{}", record);
                    } finally {
                        downloadProgressCache.remove(record.getId());
                    }
                });
            }
        }
    }

    /** 跨源匹配：按 酷我→酷狗→QQ→网易云→QQVIP 顺序搜索，返回最高音质的备选 DownloadInfo */
    private DownloadInfo tryFallback(DownloadInfo original) {
        String keyword = original.getDownloadMusicname()
                + (original.getDownloadArtistname() != null && !original.getDownloadArtistname().isEmpty()
                        ? " " + original.getDownloadArtistname().split("&")[0]
                        : "");
        List<String> order = List.of("kw", "kg", "qq", "netease", "qqvip");
        for (String plugName : order) {
            if (plugName.equals(original.getDownloadPlugName()))
                continue;
            try {
                SearchHanderAbstract alt = MusicUtils.getPlugHander(plugName, searchHanderAbstractList);
                PlugSearchResult<PlugSearchMusicResult> res = alt.querySongByName(
                        new SearchKeyData().setSearchkey(keyword).setPlugName(plugName).setPageIndex(1).setPageSize(5));
                if (res == null || res.getRecords() == null || res.getRecords().isEmpty())
                    continue;
                PlugSearchMusicResult best = res.getRecords().stream()
                        .filter(r -> r.getBrTypes() != null && !r.getBrTypes().isEmpty())
                        .max(Comparator.comparingInt(r -> MusicUtils.getMaxBr(r.getBrTypes()).getBit()))
                        .orElse(null);
                if (best == null)
                    continue;
                DownloadInfo altInfo = alt.musicToDownloadInfo(best, null, false);
                altInfo.setId(original.getId()); // 保持相同 ID，进度跟踪不断档
                log.info("跨源匹配成功: {} -> {} ({})", original.getDownloadPlugName(), plugName,
                        original.getDownloadMusicname());
                return altInfo;
            } catch (Exception ex) {
                log.warn("跨源匹配失败 plug={}: {}", plugName, ex.getMessage());
            }
        }
        return null;
    }

    // public DownloadEntity addSubsonicPlayList(DownloadEntity downloadEntity) {
    // String addSubsonicPlayListName = downloadEntity.getAddSubsonicPlayListName();
    // if (StringUtils.isNotEmpty(addSubsonicPlayListName)) {
    // log.debug("需要添加到第三方中--->{}",downloadEntity);
    // SyncTask syncTask = SpringContextUtil.getBean(SyncTask.class);
    // syncTask.excute(downloadEntity);
    // }
    //
    // return downloadEntity;
    // }

}
