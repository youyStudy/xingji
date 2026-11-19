package com.xingji.service.impl;

import com.xingji.common.BizException;
import com.xingji.config.MediaProperties;
import com.xingji.mapper.TravelMediaMapper;
import com.xingji.pojo.TravelMedia;
import com.xingji.service.TravelMediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TravelMediaServiceImpl implements TravelMediaService {

    private final TravelMediaMapper mediaMapper;
    private final MediaProperties mediaProps;

    @Override
    public TravelMedia upload(Integer userId, MultipartFile file, String purpose) {
        boolean avatar = "avatar".equals(purpose);
        // 1. 基本校验：空文件
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "文件不能为空");
        }

        // 2. 拿原始后缀名并转小写
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.contains(".")) {
            throw new BizException(400, "文件名不合法");
        }
        //  假设路径为"summer.travel.jpg" 拿到 jpg并转换小写
        String ext = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase();

        // 3. 白名单校验：后缀必须在允许的图片或视频列表里
        boolean isImage = Arrays.asList(mediaProps.getAllowedImage().split(",")).contains(ext);
        boolean isVideo = Arrays.asList(mediaProps.getAllowedVideo().split(",")).contains(ext);
        if (!isImage && !isVideo) {
            throw new BizException(400, "不支持的文件类型: " + ext);
        }
        if (avatar && !isImage) {
            throw new BizException(400, "头像只能是图片");
        }

        // 4. 大小校验：图片/视频各有各的线
        long fileSize = file.getSize();
        if (isImage && fileSize > mediaProps.getMaxImageSize()) {
            throw new BizException(400, "图片不能超过 10MB");
        }
        if (isVideo && fileSize > mediaProps.getMaxVideoSize()) {
            throw new BizException(400, "视频不能超过 50MB");
        }

        // 5. 生成存储路径：{uploadPath}/{userId}/{yyyy-MM}/{uuid}.{ext}
        // format 将LocalDateTime类型转换成字符串 DateTimeFormatter.ofPattern生成模板
        String dateDir = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        // java.util.UUID 通用唯一识别码
        // randomUUID() 静态方法 用来生成一个128位几乎不会重复的字符串ID
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        String relativePath = userId + "/" + dateDir + "/" + fileName;
        // 拼接目录
        Path fullPath = Paths.get(mediaProps.getUploadPath(), relativePath);

        // 6. 创建目录（不存在就建，已存在跳过）
        try {
            Files.createDirectories(fullPath.getParent());
        } catch (IOException e) {
            throw new RuntimeException("创建目录失败", e);
        }

        // 7. 把上传的文件写到磁盘
        try {
            file.transferTo(fullPath.toFile());
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败", e);
        }

        // 8. 入库：存相对路径（不是绝对路径，将来换OSS只改这一个字段）
        TravelMedia media = new TravelMedia();
        media.setUserId(userId);
        media.setMediaType(isImage ? "image" : "video");
        media.setFilePath(relativePath);
        media.setFileSize(fileSize);
        media.setUploadTime(LocalDateTime.now());
        // 头像只存文件、不入相册表；返回的对象没有 id
        if (!avatar) {
            mediaMapper.insert(media);
        }

        return media;
    }

    @Override
    public List<TravelMedia> listByUser(Integer userId) {
        return mediaMapper.findByUserId(userId);
    }

    @Override
    public List<TravelMedia> trashByUser(Integer userId) {
        return mediaMapper.findDeletedByUserId(userId);
    }

    @Override
    public void delete(Integer id) {
        if (mediaMapper.findById(id) == null) {
            throw new BizException(404, "文件不存在或已删除");
        }
        mediaMapper.softDelete(id);
    }

    @Override
    public void restore(Integer id) {
        mediaMapper.restore(id);
    }

    @Override
    public void clearTrash(Integer userId) {
        for (TravelMedia media : mediaMapper.findDeletedByUserId(userId)) {
            try {
                Files.deleteIfExists(Paths.get(mediaProps.getUploadPath(), media.getFilePath()));
            } catch (IOException ignored) {
                // 文件已不在也继续删库行，避免留下点不开的死条目
            }
            mediaMapper.hardDeleteById(media.getId());
        }
    }
}