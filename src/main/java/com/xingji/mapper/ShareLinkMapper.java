package com.xingji.mapper;

import com.xingji.pojo.ShareLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ShareLinkMapper {

    /** 新建一条分享链接,回填自增 id */
    int insert(ShareLink shareLink);

    /** 公开访问:按 token 查且必须 enabled=1(已撤销的链接查不到) */
    ShareLink findByValidToken(@Param("token") String token);

    /** 撤销:把某行程的所有有效链接置 enabled=0 */
    int disableByItineraryId(@Param("itineraryId") Integer itineraryId);
}
