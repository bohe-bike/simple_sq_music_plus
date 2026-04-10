-- 扩大下载错误信息字段长度，避免 255 字符被截断
ALTER TABLE `download_info`
    MODIFY COLUMN `download_msg` TEXT NULL COMMENT '下载信息错误信息';
