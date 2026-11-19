package com.xingji.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {

    private Integer id;
    private Integer userId;    // 所属用户
    private String title;      // 标题
    private String context;    // 需求上下文(JSON)
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
