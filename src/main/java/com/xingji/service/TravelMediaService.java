package com.xingji.service;

import com.xingji.pojo.TravelMedia;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

public interface TravelMediaService {

    /** 上传：校验 → 存磁盘 → 入库，返回那条记录（前端拿 id 和 filePath 展示）。purpose=avatar 时只存文件不入库（头像不进相册）。 */
    TravelMedia upload(Integer userId, MultipartFile file, String purpose);

    /** 我的图库（正常列表，排除垃圾桶） */
    List<TravelMedia> listByUser(Integer userId);

    /** 垃圾桶列表 */
    List<TravelMedia> trashByUser(Integer userId);

    /** 软删除（进垃圾桶） */
    void delete(Integer id);

    /** 还原（从垃圾桶恢复） */
    void restore(Integer id);

    /** 清空回收站：物理删除该用户所有软删素材（磁盘文件 + 数据库行） */
    void clearTrash(Integer userId);
}