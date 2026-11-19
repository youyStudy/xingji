package com.xingji.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TravelMedia {

    private Integer id;
    private Integer userId;
    private Integer itineraryId;   // 可选，先只绑 userId 时留 null
    private Integer placeId;       // 可选，绑到某个地点
    private String region;         // 地区分类，如「重庆」
    private String title;          // 照片标题，如「洪崖洞夜景」
    private String mediaType;      // image / video
    private String filePath;       // 存储路径（不是文件本身！）
    private Long fileSize;         // 字节数
    private LocalDateTime uploadTime;
    private Integer deleted;       // 0-正常 1-在垃圾桶（软删除）
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}