package com.xingji.job;

import com.xingji.config.MediaProperties;
import com.xingji.mapper.TravelMediaMapper;
import com.xingji.pojo.TravelMedia;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 素材回收站定时清理任务。
 * 用户软删除(deleted=1)的图片/视频先进"回收站",在库里保留 N 天可恢复;
 * 超过 N 天的,由本任务每天凌晨彻底物理删除(磁盘文件 + 数据库行)。
 * 这是"给删除留缓冲、又不让垃圾永久占盘"的折中,也是毕设里体现运维/自动化的一环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaCleanupJob {

    private final TravelMediaMapper mediaMapper;
    private final MediaProperties mediaProps;

    /** 回收站保留天数,默认 30;可在 yml 用 xingji.media.retention-days 覆盖 */
    @Value("${xingji.media.retention-days:30}")
    private int retentionDays;

    /** 每天凌晨 3 点跑一次(cron: 秒 分 时 日 月 周) */
    @Scheduled(cron = "0 0 3 * * ?")
    public void purgeExpiredTrash() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<TravelMedia> stale = mediaMapper.findStaleDeleted(cutoff);
        if (stale.isEmpty()) {
            log.info("[素材清理] 无超期回收项,截止 {}", cutoff);
            return;
        }
        int purged = 0;
        for (TravelMedia media : stale) {
            try {
                // 物理删文件:uploadPath + 相对路径 拼回磁盘绝对路径
                Path file = Paths.get(mediaProps.getUploadPath(), media.getFilePath());
                Files.deleteIfExists(file);   // 文件已不在也不报错
                // 文件处理掉后再删数据库行,保证不会出现"行没了文件还在"的孤儿
                mediaMapper.hardDeleteById(media.getId());
                purged++;
            } catch (Exception e) {
                // 单条失败不影响其它,记日志继续;下次仍会再尝试
                log.warn("[素材清理] 清理失败 id={} path={}: {}",
                        media.getId(), media.getFilePath(), e.getMessage());
            }
        }
        log.info("[素材清理] 本次清理超期回收项 {} 条(共 {} 条待清)", purged, stale.size());
    }
}
