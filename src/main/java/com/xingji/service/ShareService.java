package com.xingji.service;

import com.xingji.vo.ItineraryDetailVO;

/**
 * 行程分享服务(UC07)。给行程生成一条"公开可看"的链接(带随机 token),
 * 访问者无需登录、也非行程主人即可查看该行程详情;主人可随时撤销。
 */
public interface ShareService {

    /** 为某行程创建分享链接(需是该行程主人),返回 token */
    String createShare(Integer itineraryId, Integer userId);

    /** 撤销某行程的全部有效分享(需是该行程主人) */
    void revokeShare(Integer itineraryId, Integer userId);

    /** 公开访问:凭 token 取出被分享的行程详情(不校验 userId,越权校验交给 ItineraryService 之外的 token 机制) */
    ItineraryDetailVO viewByToken(String token);
}
