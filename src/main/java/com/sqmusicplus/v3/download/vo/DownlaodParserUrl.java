package com.sqmusicplus.v3.download.vo;

import lombok.Data;

import com.sqmusicplus.v3.plug.entity.Music;
import java.util.List;

/**
 * @Classname DownlaodAlubm
 * @Description 下载专辑对象
 * @Version 1.0.0
 * @Date 2022/12/14 11:18
 * @Created by shang
 */

@Data
public class DownlaodParserUrl {

    /**
     * 下载的或者解析的url地址
     */
    String url;
    /**
     * 码率
     */
    Integer br;
    /**
     * 是否是书籍类型
     */
    Boolean isAudioBook = false;
    /**
     * 书籍名称
     */
    String bookName;
    /**
     * 数据作者
     */
    String artist;

    /**
     * 插件名称
     */
    String plugType;

    /**
     * 是否开启跨源无损匹配（原始源无损时直接下载，否则按 酷我→QQ→网易云→QQVIP 顺序匹配）
     */
    Boolean crossSourceMatch;

    /**
     * 前端预解析好的歌曲列表（提供后跳过服务端二次解析）
     */
    List<Music> songs;

    /**
     * 预览任务ID，用于复用服务端缓存的完整歌曲信息
     */
    String previewJobId;

    /**
     * 前端选中的歌曲下标
     */
    List<Integer> selectedIndexes;

    public void setIsAudioBook(Boolean isAudioBook) {
        this.isAudioBook = Boolean.TRUE.equals(isAudioBook);
    }

}
