package com.xingji.controller;

import com.xingji.common.Result;
import com.xingji.common.UserContext;
import com.xingji.dto.DayRegenerateDTO;
import com.xingji.dto.DayEditDTO;
import com.xingji.dto.GenerateRequestDTO;
import com.xingji.dto.ItineraryEditDTO;
import com.xingji.dto.NodeEditDTO;
import com.xingji.pojo.Itinerary;
import com.xingji.service.ItineraryGenerateService;
import com.xingji.service.ItineraryService;
import com.xingji.service.ItineraryValidateService;
import com.xingji.vo.ItineraryDetailVO;
import com.xingji.vo.ValidationResultVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/itinerary")
@RequiredArgsConstructor
public class ItineraryController {

    private final ItineraryService itineraryService;
    private final ItineraryGenerateService itineraryGenerateService;
    private final ItineraryValidateService itineraryValidateService;

    // 获取详细行程
    @GetMapping("/{id}")
    public Result<ItineraryDetailVO> detail(@PathVariable Integer id) {
        Integer userId = UserContext.getUserId();
        return Result.ok(itineraryService.getDetail(id, userId));
    }

    @GetMapping("/list")
    public Result<List<Itinerary>> list() {
        Integer userId = UserContext.getUserId();
        return Result.ok(itineraryService.listByUser(userId));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Integer id) {
        Integer userId = UserContext.getUserId();
        itineraryService.delete(id, userId);
        return Result.ok();
    }

    @PostMapping("/generate")
    public Result<ItineraryDetailVO> generate(@Valid @RequestBody GenerateRequestDTO req) {
        Integer userId = UserContext.getUserId();
        return Result.ok(itineraryGenerateService.generate(userId, req));
    }

    // ===== 行程编辑(Phase 2b) =====

    /** 改行程主体:标题/日期/天数/预算 */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Integer id, @Valid @RequestBody ItineraryEditDTO dto) {
        Integer userId = UserContext.getUserId();
        itineraryService.updateItinerary(id, userId, dto);
        return Result.ok();
    }

    /** 给行程加一天,返回新 dayId */
    @PostMapping("/{id}/day")
    public Result<Integer> addDay(@PathVariable Integer id, @RequestBody DayEditDTO dto) {
        Integer userId = UserContext.getUserId();
        return Result.ok(itineraryService.addDay(id, userId, dto));
    }

    /** 修改某一天 */
    @PutMapping("/day/{dayId}")
    public Result<Void> updateDay(@PathVariable Integer dayId, @RequestBody DayEditDTO dto) {
        Integer userId = UserContext.getUserId();
        itineraryService.updateDay(dayId, userId, dto);
        return Result.ok();
    }

    /** 删除某一天(连带其下节点) */
    @DeleteMapping("/day/{dayId}")
    public Result<Void> deleteDay(@PathVariable Integer dayId) {
        Integer userId = UserContext.getUserId();
        itineraryService.deleteDay(dayId, userId);
        return Result.ok();
    }

    /** 给某天加一个活动节点,返回新 nodeId */
    @PostMapping("/day/{dayId}/node")
    public Result<Integer> addNode(@PathVariable Integer dayId, @RequestBody NodeEditDTO dto) {
        Integer userId = UserContext.getUserId();
        return Result.ok(itineraryService.addNode(dayId, userId, dto));
    }

    /** 修改某个活动节点 */
    @PutMapping("/node/{nodeId}")
    public Result<Void> updateNode(@PathVariable Integer nodeId, @RequestBody NodeEditDTO dto) {
        Integer userId = UserContext.getUserId();
        itineraryService.updateNode(nodeId, userId, dto);
        return Result.ok();
    }

    /** 删除某个活动节点 */
    @DeleteMapping("/node/{nodeId}")
    public Result<Void> deleteNode(@PathVariable Integer nodeId) {
        Integer userId = UserContext.getUserId();
        itineraryService.deleteNode(nodeId, userId);
        return Result.ok();
    }

    /** 校验行程(Phase 4):高德核验地点真实性 + 预算/时间约束检查,通过后状态置"已校验" */
    @PostMapping("/{id}/validate")
    public Result<ValidationResultVO> validate(@PathVariable Integer id) {
        Integer userId = UserContext.getUserId();
        return Result.ok(itineraryValidateService.validate(id, userId));
    }

    /** 置顶/取消置顶行程(列表按 pinned DESC, create_time DESC 排序) */
    @PutMapping("/{id}/pin")
    public Result<Void> pin(@PathVariable Integer id, @RequestParam boolean pinned) {
        Integer userId = UserContext.getUserId();
        itineraryService.pin(id, userId, pinned ? 1 : 0);
        return Result.ok();
    }

    /** 按天重排:只重新规划某一天(带画像+一句反馈),其余天原样保留 → 返回更新后的整棵树 */
    @PostMapping("/day/{dayId}/regenerate")
    public Result<ItineraryDetailVO> regenerateDay(
            @PathVariable Integer dayId, @Valid @RequestBody DayRegenerateDTO dto) {
        Integer userId = UserContext.getUserId();
        return Result.ok(itineraryGenerateService.regenerateDay(userId, dayId, dto));
    }
}