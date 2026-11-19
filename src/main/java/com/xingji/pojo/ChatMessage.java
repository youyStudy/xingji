package com.xingji.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    private Integer id;
    private Integer sessionId; // 所属会话
    private String role;       // user / assistant
    private String content;    // 消息正文
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
