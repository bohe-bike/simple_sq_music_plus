package com.sqmusicplus.v3.lyric;

import com.sqmusicplus.v3.base.entity.DownloadInfo;
import com.sqmusicplus.v3.download.DownloadStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricRepairServiceTest {

    @TempDir
    Path tempDir;

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
