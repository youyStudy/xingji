package com.xingji.service.impl;

import com.xingji.common.BizException;
import com.xingji.mapper.ItineraryMapper;
import com.xingji.mapper.ShareLinkMapper;
import com.xingji.pojo.Itinerary;
import com.xingji.pojo.ShareLink;
import com.xingji.service.ItineraryService;
import com.xingji.service.ShareService;
import com.xingji.vo.ItineraryDetailVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ShareServiceImpl implements ShareService {

    private final ShareLinkMapper shareLinkMapper;
    private final ItineraryMapper itineraryMapper;
    private final ItineraryService itineraryService;   // 复用"读树"能力(getDetailForShare)

    @Override
    public String createShare(Integer itineraryId, Integer userId) {
        // 只有行程主人能分享自己的行程
        Itinerary itinerary = itineraryMapper.findById(itineraryId);
        if (itinerary == null) {
            throw new BizException(404, "行程不存在");
        }
        if (!itinerary.getUserId().equals(userId)) {
            throw new BizException(403, "无权分享该行程");
        }

        // 生成不可猜测的随机 token(UUID 去横线,32 位,够长且不冲突)
        String token = UUID.randomUUID().toString().replace("-", "");
        ShareLink link = new ShareLink();
        link.setItineraryId(itineraryId);
        link.setToken(token);
        link.setEnabled(1);
        shareLinkMapper.insert(link);
        return token;
    }

    @Override
    public void revokeShare(Integer itineraryId, Integer userId) {
        Itinerary itinerary = itineraryMapper.findById(itineraryId);
        if (itinerary == null) {
            throw new BizException(404, "行程不存在");
        }
        if (!itinerary.getUserId().equals(userId)) {
            throw new BizException(403, "无权撤销该分享");
        }
        shareLinkMapper.disableByItineraryId(itineraryId);
    }

    @Override
    public ItineraryDetailVO viewByToken(String token) {
        ShareLink link = shareLinkMapper.findByValidToken(token);
        if (link == null) {
            // token 不存在或已被撤销,对访问者统一提示,不透露到底是哪种
            throw new BizException(404, "分享链接无效或已撤销");
        }
        // 访问者不是行程主人,这里走不做 userId 校验的读法
        return itineraryService.getDetailForShare(link.getItineraryId());
    }
}
