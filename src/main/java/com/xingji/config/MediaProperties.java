package com.xingji.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "xingji.media")
public class MediaProperties {

    /** 文件存储根路径（毕设=本地磁盘；将来换OSS改这里+换实现） */
    private String uploadPath;

    /** 允许的图片后缀，逗号分隔 */
    private String allowedImage;

    /** 允许的视频后缀，逗号分隔 */
    private String allowedVideo;

    /** 图片最大字节数 10MB */
    private long maxImageSize;

    /** 视频最大字节数 50MB */
    private long maxVideoSize;
}