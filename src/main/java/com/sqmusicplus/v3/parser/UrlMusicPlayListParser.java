package com.sqmusicplus.v3.parser;

import cn.hutool.core.collection.ListUtil;
import com.sqmusicplus.v3.plug.entity.Music;
import com.sqmusicplus.v3.download.vo.DownlaodParserUrl;

import com.sqmusicplus.v3.plug.entity.ParserInfo;
import com.sqmusicplus.v3.plug.kg.hander.KGHander;
import com.sqmusicplus.v3.plug.kw.hander.NKwSearchHander;
import com.sqmusicplus.v3.plug.netease.entity.PlaylistTrackAllResult;
import com.sqmusicplus.v3.plug.netease.hander.NeteaseHander;
import com.sqmusicplus.v3.plug.qq.entity.DissInfo;
import com.sqmusicplus.v3.plug.qqvip.QQvipHander;
import com.sqmusicplus.v3.utils.DownloadUtils;
import com.sqmusicplus.v3.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;

/**
 * @Classname TextMusicPlayListParser
 * @Description 文本类型歌单解析
 * @Version 1.0.0
 * @Date 2022/8/10 16:15
 * @Created by SQ
 */
@Component("urlParser")
@Slf4j
public class UrlMusicPlayListParser {

    public interface ProgressListener {
        void onProgress(ProgressSnapshot progress);
    }

    public static class ProgressSnapshot {
        private final String phase;
        private final int current;
        private final int total;
        private final String message;

        public ProgressSnapshot(String phase, int current, int total, String message) {
            this.phase = phase;
            this.current = current;
            this.total = total;
            this.message = message;
        }

        public String getPhase() {
            return phase;
        }

        public int getCurrent() {
            return current;
        }

        public int getTotal() {
            return total;
        }

        public String getMessage() {
            return message;
        }
    }

    @Autowired
    QQvipHander qqvipHander;
    @Autowired
    private NKwSearchHander nKwSearchHander;
    @Autowired
    private NeteaseHander neteaseHander;
    @Autowired
    private KGHander kgHander;

    public List<Music> parser(DownlaodParserUrl downlaodParserUrl) throws IOException {
        return parser(downlaodParserUrl, null);
    }

    public List<Music> parser(DownlaodParserUrl downlaodParserUrl, ProgressListener progressListener)
            throws IOException {
        String url = downlaodParserUrl.getUrl();
        report(progressListener, "preparing", 0, 0, "正在识别链接来源…");
        // 找出url所属的平台
        if (url.contains("y.qq.com")) {
            // 获取url的302来判断是那种类型
            OkHttpClient okHttpClient = DownloadUtils.getOkHttpClient(false);
            Request authRequest = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .get()
                    .build();
            try (Response authResponse = okHttpClient.newCall(authRequest).execute()) {
                String location = authResponse.header("Location");
                if (location != null) {
                    url = location;
                }
                ;
                return QqMusic(url, downlaodParserUrl, progressListener);
            }
        } else if (url.contains("www.kuwo.cn")) {
            // 酷我的
            if (url.contains("album") || url.contains("album_detail")) {
                // 专辑
                report(progressListener, "fetching", 0, 0, "正在获取酷我专辑曲目…");
                String[] split = url.split("/");
                String id = split[split.length - 1];
                List<Music> albumSongByAlbumsId = nKwSearchHander.queryAlbumById(id).getMusics();
                if (downlaodParserUrl.getIsAudioBook()) {
                    for (Music smusic : albumSongByAlbumsId) {
                        smusic.setMusicArtists(ListUtil.of(downlaodParserUrl.getArtist()));
                        smusic.setMusicAlbum(downlaodParserUrl.getArtist());
                    }
                }
                return albumSongByAlbumsId;
            } else if (url.contains("playlist") || url.contains("playlist_detail")) {
                // 歌单
                report(progressListener, "fetching", 0, 0, "正在获取酷我歌单分页…");
                String[] split = url.split("/");
                String id = split[split.length - 1];
                List<Music> musics = nKwSearchHander.queryAllPlayInfoList(id, 100, 1);
                if (downlaodParserUrl.getIsAudioBook()) {
                    for (Music smusic : musics) {
                        smusic.setMusicArtists(ListUtil.of(downlaodParserUrl.getArtist()));
                        smusic.setMusicAlbum(downlaodParserUrl.getArtist());
                    }
                }
                return musics;
            } else if (url.contains("yinyue") || url.contains("play_detail")) {
                String[] split = url.split("/");
                String id = split[split.length - 1];
                Music music = nKwSearchHander.querySongById(id);
                if (downlaodParserUrl.getIsAudioBook()) {
                    music.setMusicArtists(ListUtil.of(downlaodParserUrl.getArtist()));
                    music.setMusicAlbum(downlaodParserUrl.getArtist());
                }
                return List.of(music);

            } else {
                throw new RuntimeException("未知的分享类型酷我仅支持 歌单、专辑、单曲");
            }
        } else if (url.contains("music.163.com")) {
            // 专辑
            if (url.contains("album")) {
                report(progressListener, "fetching", 0, 0, "正在获取网易云专辑曲目…");
                Map<String, String> urlParams = getUrlParams(url);
                String albumId = urlParams.get("id");
                List<Music> albumSongByAlbumsId = neteaseHander.getAlbumSongByAlbumsId(albumId);
                if (downlaodParserUrl.getIsAudioBook()) {
                    for (Music smusic : albumSongByAlbumsId) {
                        smusic.setMusicArtists(ListUtil.of(downlaodParserUrl.getArtist()));
                        smusic.setMusicAlbum(downlaodParserUrl.getArtist());
                    }
                }
                return albumSongByAlbumsId;
            } else if (url.contains("playlist")) {
                Map<String, String> urlParams = getUrlParams(url);
                String playlistId = urlParams.get("id");
                ArrayList<Music> playList = neteaseHander.getPlayList(playlistId, progressListener);
                if (downlaodParserUrl.getIsAudioBook()) {
                    for (Music smusic : playList) {
                        smusic.setMusicArtists(ListUtil.of(downlaodParserUrl.getArtist()));
                        smusic.setMusicAlbum(downlaodParserUrl.getArtist());
                    }
                }
                return playList;
            } else if (url.contains("song")) {
                Map<String, String> urlParams = getUrlParams(url);
                String songId = urlParams.get("id");
                Music music = neteaseHander.querySongById(songId);
                if (downlaodParserUrl.getIsAudioBook()) {
                    music.setMusicArtists(ListUtil.of(downlaodParserUrl.getArtist()));
                    music.setMusicAlbum(downlaodParserUrl.getArtist());
                }
                return List.of(music);
            }
        } else if (url.contains("kugou.com")) {
            report(progressListener, "fetching", 0, 0, "正在识别酷狗分享链接…");
            OkHttpClient okHttpClient = DownloadUtils.getOkHttpClient(false);
            Request authRequest = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .get()
                    .build();
            try (Response authResponse = okHttpClient.newCall(authRequest).execute()) {
                String location = authResponse.header("Location");
                if (location == null) {
                    log.error("酷狗概念分享类型识别失败！");
                }
                ;
                Map<String, String> urlParams = getUrlParams(location);
                // 专辑链接判断
                if (urlParams.containsKey("specialid")) {
                    String specialId = urlParams.get("specialid");
                    report(progressListener, "fetching", 0, 0, "正在获取酷狗专辑曲目…");
                    List<Music> albumSongByAlbumsId = kgHander.getAlbumSongByAlbumsId(specialId);
                    if (downlaodParserUrl.getIsAudioBook()) {
                        for (Music smusic : albumSongByAlbumsId) {
                            smusic.setMusicArtists(ListUtil.of(downlaodParserUrl.getArtist()));
                            smusic.setMusicAlbum(downlaodParserUrl.getArtist());
                        }
                    }
                    return albumSongByAlbumsId;

                }
                // 单曲链接判断
                else if (urlParams.containsKey("album_id") && urlParams.containsKey("album_audio_id")
                        && urlParams.containsKey("hash")) {
                    String hash = urlParams.get("hash");
                    Music music = kgHander.querySongById(hash);
                    if (downlaodParserUrl.getIsAudioBook()) {
                        music.setMusicArtists(ListUtil.of(downlaodParserUrl.getArtist()));
                        music.setMusicAlbum(downlaodParserUrl.getArtist());
                    }
                    return List.of(music);
                } else {
                    throw new RuntimeException("未知的分享类型酷狗概念仅支持 专辑、单曲");
                }

            }
        } else {
            throw new RuntimeException("未知的分享类型仅支持QQ、酷狗概念版、酷我、网易云音乐");
        }
        return null;
    }

    public List<Music> QqMusic(String url, DownlaodParserUrl downlaodParserUrl, ProgressListener progressListener)
            throws MalformedURLException {
        if (url.contains("/m/share/details/album.html")) {
            // PC客户端分享专辑
            // 获取 albumId
            // https://i.y.qq.com/n2/m/share/details/album.html?ADTAG=pc_v17&albumId=3826322&channelId=10036163&openinqqmusic=1
            report(progressListener, "fetching", 0, 0, "正在获取QQ专辑曲目…");
            Map<String, String> params = getUrlParams(url);
            String albumId = params.get("albumId");
            List<Music> albumSongByAlbumsId = qqvipHander.getAlbumSongByAlbumsId(albumId);
            if (downlaodParserUrl.getIsAudioBook()) {
                for (Music smusic : albumSongByAlbumsId) {
                    smusic.setMusicArtists(ListUtil.of(downlaodParserUrl.getArtist()));
                    smusic.setMusicAlbum(downlaodParserUrl.getArtist());
                }
            }
            return albumSongByAlbumsId;
        } else if (url.contains("/m/share/details/taoge.html")) {
            //// PC客户端分享歌单
            Map<String, String> params = getUrlParams(url);
            String playListId = params.get("id");
            return parseQqPlaylist(playListId, downlaodParserUrl, progressListener);
        } else if (url.contains("/pages/playsong/index.html")) {
            // 单曲
            Map<String, String> params = getUrlParams(url);
            String songid = params.get("songid");
            // 单曲
            ArrayList<Music> musicList = new ArrayList<>();
            Music music = qqvipHander.querySongById(songid);
            musicList.add(music);
            if (downlaodParserUrl.getIsAudioBook()) {
                for (Music smusic : musicList) {
                    smusic.setMusicArtists(ListUtil.of(downlaodParserUrl.getArtist()));
                    smusic.setMusicAlbum(downlaodParserUrl.getArtist());
                }
            }
            return musicList;

        } else if (url.contains("/n/ryqq_v2/playlist/")) {
            // 网页直接复制的歌单url
            String[] parts = url.split("/");
            String playListId = parts[parts.length - 1];
            if (StringUtils.isBlank(playListId)) {
                throw new RuntimeException("请输入正确的歌单链接,或者未解析到歌单id");
            }
            return parseQqPlaylist(playListId, downlaodParserUrl, progressListener);

        } else if (url.contains("/n/ryqq_v2/albumDetail/")) {
            // 网页直接复制的歌单链接
            String[] parts = url.split("/");
            String albumId = parts[parts.length - 1];
            if (StringUtils.isBlank(albumId)) {
                throw new RuntimeException("请输入正确的专辑链接,或者未解析到专辑id");
            }
            List<Music> albumSongByAlbumsId = qqvipHander.getAlbumSongByAlbumsId(albumId);
            if (downlaodParserUrl.getIsAudioBook()) {
                for (Music smusic : albumSongByAlbumsId) {
                    smusic.setMusicArtists(ListUtil.of(downlaodParserUrl.getArtist()));
                    smusic.setMusicAlbum(downlaodParserUrl.getArtist());
                }
            }
            return albumSongByAlbumsId;
        } else if (url.contains("/n/ryqq_v2/songDetail/")) {
            // 网页直接复制的单曲链接
            String[] parts = url.split("/");
            String songid = parts[parts.length - 1];
            // 单曲
            ArrayList<Music> musicList = new ArrayList<>();
            Music music = qqvipHander.querySongById(songid);
            musicList.add(music);
            if (downlaodParserUrl.getIsAudioBook()) {
                for (Music smusic : musicList) {
                    smusic.setMusicArtists(ListUtil.of(downlaodParserUrl.getArtist()));
                    smusic.setMusicAlbum(downlaodParserUrl.getArtist());
                }
            }
            return musicList;
        }

        else if (url.contains("/n/ryqq_v2/singer")) {
            throw new RuntimeException("qq不支持歌手下载");
        } else {
            log.error("qq歌单解析失败，是否登录！");
            throw new RuntimeException("qq歌单解析失败，是否登录，未登录获取不到歌曲信息");
        }
    }

    private ArrayList<Music> parseQqPlaylist(String playListId, DownlaodParserUrl downlaodParserUrl,
            ProgressListener progressListener) {
        report(progressListener, "fetching", 0, 0, "正在获取QQ歌单首页…");
        DissInfo dissInfo = qqvipHander.songListInfo(playListId, "1418", 1);

        Long code = dissInfo.getCode();
        if (code == null || code != 0L) {
            return null;
        }
        DissInfo.DataDTO data = dissInfo.getData();
        Long totalSongNum = data.getTotalSongNum();
        Long songlistSize = data.getSonglistSize();
        List<DissInfo.DataDTO.SonglistDTO> songlist = data.getSonglist();
        long safeTotalSongNum = totalSongNum == null ? 0L : totalSongNum;
        long safeSongListSize = songlistSize == null || songlistSize == 0 ? 100L : songlistSize;
        long pageNum = Math.max(1L, (safeTotalSongNum + safeSongListSize - 1) / safeSongListSize);
        report(progressListener, "fetching", Math.min(songlist.size(), (int) safeTotalSongNum), (int) safeTotalSongNum,
                "正在抓取QQ歌单页 1 / " + pageNum);
        if (safeTotalSongNum > safeSongListSize) {
            for (int i = 2; i <= pageNum; i++) {
                report(progressListener, "fetching", Math.min(songlist.size(), (int) safeTotalSongNum),
                        (int) safeTotalSongNum, "正在抓取QQ歌单页 " + i + " / " + pageNum);
                DissInfo dissInfo1 = qqvipHander.songListInfo(playListId, "1418", i);
                DissInfo.DataDTO data1 = dissInfo1.getData();
                List<DissInfo.DataDTO.SonglistDTO> songlist1 = data1.getSonglist();
                songlist.addAll(songlist1);
            }
        }
        report(progressListener, "resolving", 0, songlist.size(), "正在读取QQ歌曲详情…0 / " + songlist.size());
        ArrayList<Music> musicList = new ArrayList<>();
        for (int i = 0; i < songlist.size(); i++) {
            DissInfo.DataDTO.SonglistDTO item = songlist.get(i);
            String songmid = item.getMid();
            Music music = qqvipHander.querySongById(songmid);
            if (downlaodParserUrl.getIsAudioBook()) {
                music.setMusicArtists(ListUtil.of(downlaodParserUrl.getArtist()));
                music.setMusicAlbum(downlaodParserUrl.getArtist());
            }
            musicList.add(music);
            if ((i + 1) == songlist.size() || (i + 1) % 20 == 0) {
                report(progressListener, "resolving", i + 1, songlist.size(),
                        "正在读取QQ歌曲详情…" + (i + 1) + " / " + songlist.size());
            }
        }
        return musicList;
    }

    private void report(ProgressListener progressListener, String phase, int current, int total, String message) {
        if (progressListener == null) {
            return;
        }
        progressListener.onProgress(new ProgressSnapshot(phase, current, total, message));
    }

    @NotNull
    private static Map<String, String> getUrlParams(String url) throws MalformedURLException {
        URI uri = null;
        try {
            uri = new URL(url).toURI();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        String query = uri.getQuery();
        if (query == null || query.isEmpty()) {
            String fragment = uri.getFragment();
            if (fragment != null && !fragment.isEmpty()) {
                int queryIndex = fragment.indexOf('?');
                if (queryIndex != -1) {
                    query = fragment.substring(queryIndex + 1);
                }
            }
        }

        log.debug("解析 URL: {}, 获取到的 query: {}", url, query);
        Map<String, String> params = new HashMap<>();
        if (query != null && !query.isEmpty()) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                params.put(pair[0], pair.length > 1 ? pair[1] : null);
            }
        }
        return params;
    }

    public ParserInfo parserUrlInfo(String url) throws MalformedURLException {
        // 找出url所属的平台
        if (url.contains("music.163.com")) {
            if (url.contains("playlist")) {
                Map<String, String> urlParams = getUrlParams(url);
                String playlistId = urlParams.get("id");
                PlaylistTrackAllResult playListInfo = neteaseHander.getPlayListInfo(playlistId);
                PlaylistTrackAllResult.playlist playlist = playListInfo.getPlaylist();
                ParserInfo parserInfo = new ParserInfo();
                parserInfo.setName(playlist.getName());
                parserInfo.setPlugNmae(neteaseHander.getPlugName());
                parserInfo.setId(playlistId);
                parserInfo.setUrl(url);
                parserInfo.setType("playlist");
                parserInfo.setCount(playlist.getTrackCount());
                parserInfo.setDesc(playlist.getDescription());
                parserInfo.setCover(playlist.getCoverImgUrl());
                return parserInfo;
            }
        }
        throw new RuntimeException("目前仅支持网易云歌单，需要其他的issues留言！");

    }

}
