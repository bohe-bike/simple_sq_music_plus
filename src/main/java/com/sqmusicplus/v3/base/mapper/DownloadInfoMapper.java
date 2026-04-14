package com.sqmusicplus.v3.base.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sqmusicplus.v3.base.entity.DownloadInfo;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * Mapper 接口
 * </p>
 *
 * @author sq
 * @since 2023-08-23
 */
@Mapper
public interface DownloadInfoMapper extends BaseMapper<DownloadInfo> {

    /**
     * 查询要保留的记录 ID（每组重复中 id 最大的那条）
     */
    @Select("SELECT MAX(id) FROM download_info " +
            "GROUP BY download_musicname, download_artistname, download_plug_name, download_status")
    List<Integer> selectKeepIds();

    /**
     * 删除不在保留列表中的重复记录
     * 注意：子查询套一层是为了兼容 MySQL 不允许在同一张表上 DELETE+SELECT 的限制
     */
    @Delete("DELETE FROM download_info WHERE id NOT IN (" +
            "SELECT max_id FROM (" +
            "SELECT MAX(id) AS max_id FROM download_info " +
            "GROUP BY download_musicname, download_artistname, download_plug_name, download_status" +
            ") t)")
    int deleteDuplicates();

}
