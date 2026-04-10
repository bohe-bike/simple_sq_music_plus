package com.sqmusicplus.v3.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.sqmusicplus.v3.base.entity.DownloadInfo;
import com.sqmusicplus.v3.base.entity.vo.ParserEntity;
import com.sqmusicplus.v3.base.enums.PlugBrType;
import com.sqmusicplus.v3.base.service.DownloadInfoService;
import com.sqmusicplus.v3.config.AjaxResult;
import com.sqmusicplus.v3.config.exception.SQException;
import com.sqmusicplus.v3.download.ParseJobCache;
import com.sqmusicplus.v3.download.vo.DownlaodParserUrl;
import com.sqmusicplus.v3.download.vo.ParserTextParam;
import com.sqmusicplus.v3.parser.TextMusicPlayListParser;
import com.sqmusicplus.v3.parser.UrlMusicPlayListParser;
import com.sqmusicplus.v3.plug.base.hander.SearchHanderAbstract;
import com.sqmusicplus.v3.plug.entity.*;
import com.sqmusicplus.v3.utils.MusicUtils;
import com.sqmusicplus.v3.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * @Classname DownloadServiceController
 * @Description 下载到服务器控制接口
 * @Version 1.0.0
 * @Date 2025/7/25 10:29
 * @Created by SQ
 */
@Slf4j
@RestController
@RequestMapping("/api/download")
public class DownloadServiceController {

    @Autowired
    List<SearchHanderAbstract> searchHanderAbstractList;

    @Autowired
    private DownloadInfoService downloadInfoService;

    @Autowired
    private UrlMusicPlayListParser urlMusicPlayListParser;
    @Autowired
    private TextMusicPlayListParser textMusicPlayListParser;
    @Autowired
    private ParseJobCache parseJobCache;
    @Autowired
    @Qualifier("threadPoolTaskExecutor")
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    /**
     * 下载单曲
     * 
     * @param downloadSongParam
     * @return
     */
    @SaCheckLogin
    @PostMapping("/downloadSong")
    public AjaxResult downloadSong(@RequestBody PlugDownloadSongParam downloadSongParam) {
        SearchHanderAbstract plugHander = MusicUtils.getPlugHander(downloadSongParam.getPlugName(),
                searchHanderAbstractList);
        List<PlugBrType> brTypes = downloadSongParam.getBrTypes();
        if (brTypes == null || brTypes.isEmpty()) {
            throw new SQException("未找到可供下载的bit");
        }
        PlugBrType maxBr = MusicUtils.getMaxBr(brTypes);
        if (downloadSongParam.getBrType() != null) {
            maxBr = downloadSongParam.getBrType();
        }
        DownloadInfo downloadInfo = plugHander.musicToDownloadInfo(downloadSongParam, maxBr, false);
        Boolean add = downloadInfoService.add(downloadInfo);
        if (add) {
            return AjaxResult.success("下载成功", downloadInfo);
        }
        return AjaxResult.error("下载失败");
    }

    /**
     * 下载歌手的全部专辑
     * 
     * @param plugDownloadArtisParam
     * @return
     */
    @SaCheckLogin
    @PostMapping("/downloadArtistAlbum")
    public AjaxResult downloadArtistAlbum(@RequestBody PlugDownloadArtisParam plugDownloadArtisParam) {
        SearchHanderAbstract plugHander = MusicUtils.getPlugHander(plugDownloadArtisParam.getPlugName(),
                searchHanderAbstractList);
        PlugBrType maxBr = null;
        if (plugDownloadArtisParam.getBit() != null) {
            maxBr = PlugBrType.findByPlugNameAndBit(plugDownloadArtisParam.getPlugName(),
                    plugDownloadArtisParam.getBit());
        }
        List<DownloadInfo> downloadInfos = plugHander.downloadArtistAllAlbum(plugDownloadArtisParam.getArtistid(),
                maxBr);
        Boolean add = downloadInfoService.add(downloadInfos);
        if (add) {
            return AjaxResult.success("下载成功", downloadInfos);
        }
        return AjaxResult.error("下载失败");
    }

    /**
     * 下载专辑
     * 
     * @param plugDownloadAlbumParam
     * @return
     */
    @SaCheckLogin
    @PostMapping("/downloadAlbum")
    public AjaxResult downloadAlbum(@RequestBody PlugDownloadAlbumParam plugDownloadAlbumParam) {
        SearchHanderAbstract plugHander = MusicUtils.getPlugHander(plugDownloadAlbumParam.getPlugName(),
                searchHanderAbstractList);
        PlugBrType maxBr = null;
        if (plugDownloadAlbumParam.getBit() != null) {
            maxBr = PlugBrType.findByPlugNameAndBit(plugDownloadAlbumParam.getPlugName(),
                    plugDownloadAlbumParam.getBit());
        }
        List<String> artistNameList = null;
        String albumid = plugDownloadAlbumParam.getAlbumid();
        if (StringUtils.isNotBlank(plugDownloadAlbumParam.getArtistName())) {
            String[] split = plugDownloadAlbumParam.getArtistName().split("&");
            artistNameList = new ArrayList<>();
            for (String s : split) {
                artistNameList.add(s.trim());
            }
        }
        ArrayList<DownloadInfo> downloadInfos = plugHander.downloadAlbum(albumid, maxBr, artistNameList, false,
                plugDownloadAlbumParam.getAlbumName());
        Boolean add = downloadInfoService.add(downloadInfos);
        if (add) {
            return AjaxResult.success("下载成功", downloadInfos);
        }
        return AjaxResult.error("下载失败");
    }

    /**
     * 下载解析的URL歌曲
     * 
     * @param downlaodParserUrl 解析的URL
     * @return
     */
    @SaCheckLogin
    @PostMapping("/downloadParserUrl")
    public AjaxResult downloadParserUrl(@RequestBody DownlaodParserUrl downlaodParserUrl) {
        String jobId = UUID.randomUUID().toString();
        parseJobCache.create(jobId);
        threadPoolTaskExecutor.execute(() -> doParseAndDownload(jobId, downlaodParserUrl));
        return AjaxResult.success("已提交解析任务", jobId);
    }

    /**
     * 预览歌单曲目（异步，不入队，通过 parseJobStatus 轮询结果）
     */
    @SaCheckLogin
    @PostMapping("/previewParserUrl")
    public AjaxResult previewParserUrl(@RequestBody DownlaodParserUrl downlaodParserUrl) {
        String jobId = UUID.randomUUID().toString();
        parseJobCache.create(jobId);
        threadPoolTaskExecutor.execute(() -> doPreview(jobId, downlaodParserUrl));
        return AjaxResult.success("已提交预览任务", jobId);
    }

    /** 后台异步执行歌单解析（仅预览，不入队） */
    private void doPreview(String jobId, DownlaodParserUrl downlaodParserUrl) {
        try {
            parseJobCache.updateStage(jobId, "preparing", 0, 0, "正在识别链接来源…");
            List<Music> list = urlMusicPlayListParser.parser(downlaodParserUrl,
                    progress -> parseJobCache.updateStage(jobId, progress.getPhase(), progress.getCurrent(),
                            progress.getTotal(), progress.getMessage()));
            if (list == null || list.isEmpty()) {
                parseJobCache.error(jobId, "解析失败，仅支持 QQ/酷我/酷狗/网易云");
                return;
            }
            parseJobCache.previewDone(jobId, list);
        } catch (Exception e) {
            log.error("预览解析失败 jobId={}", jobId, e);
            parseJobCache.error(jobId, e.getMessage() != null ? e.getMessage() : "未知错误");
        }
    }

    /**
     * 查询歌单解析任务进度
     */
    @GetMapping("/parseJobStatus/{jobId}")
    public AjaxResult parseJobStatus(@PathVariable String jobId) {
        ParseJobCache.ParseJobStatus status = parseJobCache.get(jobId);
        if (status == null) {
            return AjaxResult.error("任务不存在或已过期");
        }
        return AjaxResult.success(status);
    }

    /** 异步执行歌单解析与下载任务入队 */
    private void doParseAndDownload(String jobId, DownlaodParserUrl downlaodParserUrl) {
        try {
            // 如果前端已预解析并传回了歌曲列表，直接使用，避免二次解析
            List<Music> parser;
            if (StringUtils.isNotBlank(downlaodParserUrl.getPreviewJobId())) {
                parser = resolvePreviewSongs(downlaodParserUrl);
                if (parser == null) {
                    parseJobCache.error(jobId, "预览结果已过期，请重新解析歌单");
                    return;
                }
                if (parser.isEmpty()) {
                    parseJobCache.error(jobId, "未选择有效歌曲");
                    return;
                }
            } else if (downlaodParserUrl.getSongs() != null && !downlaodParserUrl.getSongs().isEmpty()) {
                parser = downlaodParserUrl.getSongs();
            } else {
                parser = urlMusicPlayListParser.parser(downlaodParserUrl);
            }
            if (parser == null || parser.isEmpty()) {
                parseJobCache.error(jobId, "解析失败，仅支持 QQ/酷我/酷狗/网易云");
                return;
            }
            int total = parser.size();
            parseJobCache.updateFetching(jobId, total);

            ArrayList<DownloadInfo> downloadInfos = new ArrayList<>();
            boolean crossSourceMatch = Boolean.TRUE.equals(downlaodParserUrl.getCrossSourceMatch());
            List<String> crossSourceOrder = List.of("kw", "kg", "qq", "netease", "qqvip");

            for (int i = 0; i < parser.size(); i++) {
                Music music = parser.get(i);
                SearchHanderAbstract origHander = MusicUtils.getPlugHander(music.getPlugName(),
                        searchHanderAbstractList);
                if (crossSourceMatch && !hasLossless(music.getBits())) {
                    parseJobCache.updateMatching(jobId, i + 1, total);
                    DownloadInfo matched = null;
                    for (String plugName : crossSourceOrder) {
                        if (plugName.equals(music.getPlugName()))
                            continue;
                        try {
                            SearchHanderAbstract altHander = MusicUtils.getPlugHander(plugName,
                                    searchHanderAbstractList);
                            String keyword = buildCrossSearchKeyword(music);
                            SearchKeyData keyData = new SearchKeyData()
                                    .setSearchkey(keyword)
                                    .setPlugName(plugName)
                                    .setPageIndex(1)
                                    .setPageSize(5);
                            PlugSearchResult<PlugSearchMusicResult> result = altHander.querySongByName(keyData);
                            if (result == null || result.getRecords() == null)
                                continue;
                            for (PlugSearchMusicResult r : result.getRecords()) {
                                if (hasLossless(r.getBrTypes())) {
                                    matched = altHander.musicToDownloadInfo(r, null, false);
                                    break;
                                }
                            }
                            if (matched != null)
                                break;
                        } catch (Exception e) {
                            log.warn("跨源匹配失败 plug={}, music={}", plugName, music.getMusicName(), e);
                        }
                    }
                    downloadInfos.add(matched != null ? matched : origHander.musicToDownloadInfo(music, null, false));
                } else {
                    parseJobCache.updateBuilding(jobId, i + 1, total);
                    downloadInfos.add(origHander.musicToDownloadInfo(music, null, false));
                }
            }
            downloadInfoService.add(downloadInfos);
            parseJobCache.done(jobId, downloadInfos.size());
        } catch (Exception e) {
            log.error("歌单异步解析失败 jobId={}", jobId, e);
            parseJobCache.error(jobId, e.getMessage() != null ? e.getMessage() : "未知错误");
        }
    }

    private List<Music> resolvePreviewSongs(DownlaodParserUrl downlaodParserUrl) {
        List<Music> rawSongs = parseJobCache.getRawSongs(downlaodParserUrl.getPreviewJobId());
        if (rawSongs == null || rawSongs.isEmpty()) {
            return null;
        }
        List<Integer> selectedIndexes = downlaodParserUrl.getSelectedIndexes();
        if (selectedIndexes == null || selectedIndexes.isEmpty()) {
            return new ArrayList<>(rawSongs);
        }
        ArrayList<Music> selectedSongs = new ArrayList<>(selectedIndexes.size());
        for (Integer selectedIndex : selectedIndexes) {
            if (selectedIndex == null || selectedIndex < 0 || selectedIndex >= rawSongs.size()) {
                continue;
            }
            selectedSongs.add(rawSongs.get(selectedIndex));
        }
        return selectedSongs;
    }

    private boolean hasLossless(List<PlugBrType> bits) {
        if (bits == null || bits.isEmpty())
            return false;
        return bits.stream().anyMatch(b -> b.getBit() >= 2000
                || "flac".equalsIgnoreCase(b.getType())
                || "ape".equalsIgnoreCase(b.getType()));
    }

    private String buildCrossSearchKeyword(Music music) {
        String name = music.getMusicName() != null ? music.getMusicName() : "";
        if (music.getMusicArtists() != null && !music.getMusicArtists().isEmpty()) {
            name = name + " " + music.getMusicArtists().get(0);
        }
        return name.trim();
    }

    /**
     * 下载解析的URL歌曲（替代解析方法）
     * 
     * @param musicList
     * @return
     */
    @SaCheckLogin
    @PostMapping("/downloadParserUrlResult")
    public AjaxResult downloadParserUrlResult(@RequestBody List<Music> musicList) {
        ArrayList<DownloadInfo> downloadInfos = new ArrayList<>();
        for (Music music : musicList) {
            SearchHanderAbstract plugHander = MusicUtils.getPlugHander(music.getPlugName(), searchHanderAbstractList);
            DownloadInfo downloadInfo = plugHander.musicToDownloadInfo(music, null, false);
            downloadInfos.add(downloadInfo);
        }
        Boolean add = downloadInfoService.add(downloadInfos);
        if (add) {
            return AjaxResult.success("下载成功", downloadInfos);
        }
        return AjaxResult.error("下载失败");
    }

    /**
     * 下载解析的文本歌曲(替代解析方法)
     * 
     * @param param
     * @return
     */
    @SaCheckLogin
    @PostMapping("/downloadParserText")
    public AjaxResult downloadParserText(@RequestBody ParserTextParam param) {
        if (StringUtils.isBlank(param.getText())) {
            return AjaxResult.error("请输入要解析的文本");
        }
        Thread thread = new Thread(() -> {
            try {
                List<ParserEntity> parser = textMusicPlayListParser.parser(param.getText());
                List<ParserEntity> parserEntities = textMusicPlayListParser.parserParserEntity(parser);

                if (parserEntities != null) {
                    try {
                        ArrayList<DownloadInfo> downloadInfos = new ArrayList<>();
                        for (ParserEntity parserEntity : parserEntities) {
                            PlugSearchMusicResult plugSearchMusicResult = parserEntity.getPlugSearchMusicResult();
                            if (StringUtils.isBlank(plugSearchMusicResult.getPlugName())) {
                                continue;
                            }
                            SearchHanderAbstract plugHander = MusicUtils
                                    .getPlugHander(plugSearchMusicResult.getPlugName(), searchHanderAbstractList);
                            DownloadInfo downloadInfo = plugHander.musicToDownloadInfo(plugSearchMusicResult, null,
                                    false);
                            downloadInfos.add(downloadInfo);
                        }
                        downloadInfoService.add(downloadInfos);
                    } catch (Exception ignored) {

                    }
                }
            } catch (Exception e) {
                log.error("解析失败", e);
            }
        });
        thread.start();
        return AjaxResult.success("开始解析并下载，稍后在下载中查看！（每首识别大致需要500毫秒耐心等待）");
    }

    /**
     * 批量下载解析的文本歌曲 替代解析方法
     * 
     * @param parserEntities
     * @return
     */
    @SaCheckLogin
    @PostMapping("/downloadParserTextResult")
    public AjaxResult downloadParserTextResult(@RequestBody List<ParserEntity> parserEntities) {
        ArrayList<DownloadInfo> downloadInfos = new ArrayList<>();
        for (ParserEntity parserEntity : parserEntities) {
            PlugSearchMusicResult plugSearchMusicResult = parserEntity.getPlugSearchMusicResult();
            SearchHanderAbstract plugHander = MusicUtils.getPlugHander(plugSearchMusicResult.getPlugName(),
                    searchHanderAbstractList);
            DownloadInfo downloadInfo = plugHander.musicToDownloadInfo(plugSearchMusicResult, null, false);
            downloadInfos.add(downloadInfo);
        }
        Boolean add = downloadInfoService.add(downloadInfos);
        if (add) {
            return AjaxResult.success("下载成功", downloadInfos);
        }
        return AjaxResult.error("下载失败");
    }

}
