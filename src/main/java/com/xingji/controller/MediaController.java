package com.xingji.controller;

import com.xingji.common.Result;
import com.xingji.common.UserContext;
import com.xingji.pojo.TravelMedia;
import com.xingji.service.TravelMediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 媒体上传与图库管理（travel_media）。所有接口需登录，从 UserContext 拿 userId。 */
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final TravelMediaService mediaService;

    // 上传
    @PostMapping("/upload")
    // MultipartFile是 Spring MVC 提供的接口,专门用来接收前端上传的文件
    public Result<TravelMedia> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) String purpose) {
        Integer userId = UserContext.getUserId();
        TravelMedia media = mediaService.upload(userId, file, purpose);
        return Result.ok(media);
    }

    @GetMapping("/list")
    public Result<List<TravelMedia>> list() {
        Integer userId = UserContext.getUserId();
        return Result.ok(mediaService.listByUser(userId));
    }

    // 获取垃圾桶里的照片
    @GetMapping("/trash")
    public Result<List<TravelMedia>> trash() {
        Integer userId = UserContext.getUserId();
        return Result.ok(mediaService.trashByUser(userId));
    }

    // 清空回收站（彻底删除）
    @DeleteMapping("/trash")
    public Result<Void> clearTrash() {
        mediaService.clearTrash(UserContext.getUserId());
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Integer id) {
        mediaService.delete(id);
        return Result.ok();
    }

    // 恢复垃圾桶的照片
    @PutMapping("/{id}/restore")
    public Result<Void> restore(@PathVariable Integer id) {
        mediaService.restore(id);
        return Result.ok();
    }
}