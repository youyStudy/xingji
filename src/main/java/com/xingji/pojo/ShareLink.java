package com.xingji.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShareLink {

    private Integer id;
    private Integer itineraryId; // 被分享行程
    private String token;        // 分享令牌(公开访问)
    private Integer enabled;     // 1-有效 0-已撤销
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
