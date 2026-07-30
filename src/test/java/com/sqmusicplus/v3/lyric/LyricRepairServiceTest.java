package com.sqmusicplus.v3.lyric;

import com.sqmusicplus.v3.base.entity.DownloadInfo;
import com.sqmusicplus.v3.download.DownloadStatus;
import com.sqmusicplus.v3.plug.base.hander.SearchHanderAbstract;
import com.sqmusicplus.v3.plug.entity.PlugSearchMusicResult;
import com.sqmusicplus.v3.plug.entity.PlugSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LyricRepairServiceTest {

    @TempDir
    Path tempDir;

    private LyricRepairService service;

    @BeforeEach
    void setUp() {
        service = new LyricRepairService();
    }

    @Test
    void matchesDefaultDownloadPathAgainstHistory() throws Exception {
        Path audio = tempDir.resolve("凤凰传奇").resolve("最炫民族风")
                .resolve("最炫民族风 - 凤凰传奇.flac");
        Files.createDirectories(audio.getParent());
        Files.createFile(audio);

        DownloadInfo record = record("kw", "123", "最炫民族风", "凤凰传奇", "最炫民族风");
        LyricRepairService.DownloadRecordIndex index = new LyricRepairService.DownloadRecordIndex(List.of(record));

        LyricRepairService.MatchResult result = index.match(audio, tempDir);

        assertTrue(result.isMatched());
        assertEquals("123", result.getRecord().getDownloadMusicId());
    }

    @Test
    void rejectsAmbiguousHistoryMatches() throws Exception {
        Path audio = tempDir.resolve("歌手").resolve("专辑").resolve("歌曲 - 歌手.flac");
        Files.createDirectories(audio.getParent());
        Files.createFile(audio);

        DownloadInfo first = record("kw", "1", "歌曲", "歌手", "专辑");
        DownloadInfo second = record("qq", "2", "歌曲", "歌手", "专辑");
        LyricRepairService.DownloadRecordIndex index = new LyricRepairService.DownloadRecordIndex(
                List.of(first, second));

        LyricRepairService.MatchResult result = index.match(audio, tempDir);

        assertFalse(result.isMatched());
        assertTrue(result.getMessage().contains("多个"));
    }

    @Test
    void writesUtf8SidecarWithoutTouchingAudioFile() throws Exception {
        Path audio = tempDir.resolve("song.flac");
        Files.write(audio, new byte[] { 1, 2, 3 });
        Path lyricFile = LyricRepairService.sidecarPath(audio);

        LyricRepairService.writeLyricFile(lyricFile, "[00:01.00]测试歌词\n");

        assertEquals("[00:01.00]测试歌词\n", Files.readString(lyricFile, StandardCharsets.UTF_8));
        assertEquals(3, Files.size(audio));
    }

    @Test
    void keepsOriginalSourceWhenItReturnsLyrics() {
        SearchHanderAbstract original = handler("kw");
        SearchHanderAbstract fallback = handler("netease");
        when(original.queryLyric("1")).thenReturn("[00:01]original");
        setHandlers(original, fallback);

        LyricRepairService.LyricLookupResult result = service.lookupLyrics(
                record("kw", "1", "歌曲", "歌手", "专辑"), true, new HashMap<>(), List.of(fallback));

        assertTrue(result.hasLyric());
        assertFalse(result.crossSource());
        assertEquals("kw", result.plugName());
        verify(fallback, never()).querySongByName(any());
    }

    @Test
    void findsLyricsFromAlternateSourceAfterOriginalIsEmpty() {
        SearchHanderAbstract original = handler("kw");
        SearchHanderAbstract fallback = handler("netease");
        when(original.queryLyric("1")).thenReturn("");
        when(fallback.querySongByName(any())).thenReturn(searchResult(song("2", "歌曲", "歌手", "专辑")));
        when(fallback.queryLyric("2")).thenReturn("[00:01]fallback");
        setHandlers(original, fallback);

        LyricRepairService.LyricLookupResult result = service.lookupLyrics(
                record("kw", "1", "歌曲", "歌手", "专辑"), true, new HashMap<>(), List.of(fallback));

        assertTrue(result.hasLyric());
        assertTrue(result.crossSource());
        assertEquals("netease", result.plugName());
        assertEquals("2", result.musicId());
    }

    @Test
    void rejectsSameTitleFromWrongArtist() {
        DownloadInfo record = record("kw", "1", "歌曲", "歌手甲", "专辑");

        PlugSearchMusicResult candidate = LyricRepairService.selectLyricCandidate(record,
                List.of(song("2", "歌曲", "歌手乙", "专辑")));

        assertNull(candidate);
    }

    @Test
    void rejectsAmbiguousAlternateResults() {
        DownloadInfo record = record("kw", "1", "歌曲", "歌手", "");

        PlugSearchMusicResult candidate = LyricRepairService.selectLyricCandidate(record,
                List.of(song("2", "歌曲", "歌手", "版本一"), song("3", "歌曲", "歌手", "版本二")));

        assertNull(candidate);
    }

    @Test
    void skipsDisabledAndOriginalSourceFallbackHandlers() {
        SearchHanderAbstract kw = handler("kw");
        SearchHanderAbstract netease = handler("netease");
        SearchHanderAbstract qqvip = handler("qqvip");
        SearchHanderAbstract kg = handler("kg");

        List<SearchHanderAbstract> selected = LyricRepairService.selectFallbackHandlers("qq",
                List.of(kw, netease, qqvip, kg), plugName -> "netease".equals(plugName));

        assertEquals(List.of(netease), selected);
    }

    @Test
    void alternateSourceErrorsDoNotFailLookup() {
        SearchHanderAbstract failing = handler("netease");
        SearchHanderAbstract succeeding = handler("kg");
        when(failing.querySongByName(any())).thenThrow(new IllegalStateException("network"));
        when(succeeding.querySongByName(any())).thenReturn(searchResult(song("2", "歌曲", "歌手", "专辑")));
        when(succeeding.queryLyric("2")).thenReturn("[00:01]lyrics");

        LyricRepairService.LyricLookupResult result = service.searchCrossSources(
                record("kw", "1", "歌曲", "歌手", "专辑"), List.of(failing, succeeding));

        assertTrue(result.hasLyric());
        assertEquals("kg", result.plugName());
    }

    @Test
    void missingOriginalHandlerStillUsesAlternateSource() {
        SearchHanderAbstract fallback = handler("netease");
        when(fallback.querySongByName(any())).thenReturn(searchResult(song("2", "歌曲", "歌手", "专辑")));
        when(fallback.queryLyric("2")).thenReturn("[00:01]fallback");
        setHandlers(fallback);

        LyricRepairService.LyricLookupResult result = service.lookupLyrics(
                record("removed", "1", "歌曲", "歌手", "专辑"), true, new HashMap<>(), List.of(fallback));

        assertTrue(result.hasLyric());
        assertEquals("netease", result.plugName());
    }

    @Test
    void cachesCrossSourceLookupPerSongWithinTask() {
        SearchHanderAbstract original = handler("kw");
        SearchHanderAbstract fallback = handler("netease");
        when(original.queryLyric("1")).thenReturn("");
        when(fallback.querySongByName(any())).thenReturn(searchResult(song("2", "歌曲", "歌手", "专辑")));
        when(fallback.queryLyric("2")).thenReturn("[00:01]fallback");
        setHandlers(original, fallback);
        HashMap<String, LyricRepairService.LyricLookupResult> cache = new HashMap<>();
        DownloadInfo record = record("kw", "1", "歌曲", "歌手", "专辑");

        service.lookupLyrics(record, true, cache, List.of(fallback));
        service.lookupLyrics(record, true, cache, List.of(fallback));

        verify(fallback).querySongByName(any());
    }

    private void setHandlers(SearchHanderAbstract... handlers) {
        ReflectionTestUtils.setField(service, "searchHanderAbstractList", List.of(handlers));
    }

    private SearchHanderAbstract handler(String plugName) {
        SearchHanderAbstract handler = mock(SearchHanderAbstract.class);
        when(handler.getPlugName()).thenReturn(plugName);
        return handler;
    }

    private PlugSearchResult<PlugSearchMusicResult> searchResult(PlugSearchMusicResult... songs) {
        return new PlugSearchResult<PlugSearchMusicResult>().setRecords(new ArrayList<>(List.of(songs)));
    }

    private PlugSearchMusicResult song(String id, String name, String artist, String album) {
        return new PlugSearchMusicResult()
                .setId(id)
                .setName(name)
                .setArtistName(List.of(artist))
                .setAlbumName(album);
    }

    private DownloadInfo record(String plugName, String musicId, String musicName, String artist, String album) {
        return new DownloadInfo()
                .setDownloadPlugName(plugName)
                .setDownloadMusicId(musicId)
                .setDownloadMusicname(musicName)
                .setDownloadArtistname(artist)
                .setDownloadAlbumname(album)
                .setDownloadFile(musicName + " - " + artist)
                .setDownloadStatus(DownloadStatus.success.getValue());
    }
}
