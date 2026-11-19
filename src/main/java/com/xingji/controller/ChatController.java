package com.xingji.controller;

import com.xingji.common.Result;
import com.xingji.common.UserContext;
import com.xingji.dto.ChatSendDTO;
import com.xingji.pojo.ChatSession;
import com.xingji.service.ChatService;
import com.xingji.vo.ChatSessionVO;
import com.xingji.vo.ChatTurnVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 对话接口(UC02,需登录):管理会话、发消息拿模型回复。 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /** 新建会话,返回会话 id */
    @PostMapping("/session")
    public Result<Integer> createSession(@RequestParam(required = false) String title) {
        Integer userId = UserContext.getUserId();
        return Result.ok(chatService.createSession(userId, title));
    }

    /** 我的会话列表 */
    @GetMapping("/sessions")
    public Result<List<ChatSession>> sessions() {
        Integer userId = UserContext.getUserId();
        return Result.ok(chatService.listSessions(userId));
    }

    /** 某会话详情(含历史消息) */
    @GetMapping("/session/{id}")
    public Result<ChatSessionVO> sessionDetail(@PathVariable Integer id) {
        Integer userId = UserContext.getUserId();
        return Result.ok(chatService.getSession(id, userId));
    }

    /** 发一条消息:返回助手回复 + 服务端合并后的画像 + 缺失项 + 下一步动作 */
    @PostMapping("/message")
    public Result<ChatTurnVO> send(@Valid @RequestBody ChatSendDTO dto) {
        Integer userId = UserContext.getUserId();
        return Result.ok(chatService.send(userId, dto));
    }

    /** 删除会话(连带清空消息) */
    @DeleteMapping("/session/{id}")
    public Result<Void> deleteSession(@PathVariable Integer id) {
        Integer userId = UserContext.getUserId();
        chatService.deleteSession(id, userId);
        return Result.ok();
    }
}
