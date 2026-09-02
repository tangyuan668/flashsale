package com.flashsale.activity.controller;

import com.flashsale.activity.dto.ActivityCreateRequest;
import com.flashsale.activity.dto.ActivityItemAddRequest;
import com.flashsale.activity.service.ActivityService;
import com.flashsale.activity.vo.ActivityVO;
import com.flashsale.common.Result;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 活动控制器
 */
@Slf4j
@RestController
@RequestMapping("/activity")
public class ActivityController {

    @Autowired
    private ActivityService activityService;

    /**
     * 创建活动
     * POST /api/activity/create
     */
    @PostMapping("/create")
    public Result<Long> createActivity(@Valid @RequestBody ActivityCreateRequest request) {
        Long activityId = activityService.createActivity(request);
        return Result.ok("创建成功", activityId);
    }

    /**
     * 获取活动列表
     * GET /api/activity/list
     */
    @GetMapping("/list")
    public Result<List<ActivityVO>> getActivityList() {
        List<ActivityVO> list = activityService.getActivityList();
        return Result.ok(list);
    }

    /**
     * 获取活动详情
     * GET /api/activity/{id}
     */
    @GetMapping("/{id}")
    public Result<ActivityVO> getActivityDetail(@PathVariable("id") Long id) {
        ActivityVO activity = activityService.getActivityDetail(id);
        return Result.ok(activity);
    }

    /**
     * 库存预热
     * POST /api/activity/{id}/preheat
     */
    @PostMapping("/{id}/preheat")
    public Result<Void> preheatStock(@PathVariable("id") Long activityId) {
        activityService.preheatStock(activityId);
        return Result.ok("库存预热成功", null);
    }

    /**
     * 更新活动状态
     * PUT /api/activity/{id}/status
     */
    @PutMapping("/{id}/status")
    public Result<Void> updateActivityStatus(@PathVariable("id") Long activityId,
                                              @RequestParam("status") Integer status) {
        activityService.updateActivityStatus(activityId, status);
        return Result.ok("状态更新成功", null);
    }

    /**
     * 给活动添加秒杀商品
     * POST /api/activity/{id}/items
     */
    @PostMapping("/{id}/items")
    public Result<Void> addItem(@PathVariable("id") Long activityId,
                                @Valid @RequestBody ActivityItemAddRequest request) {
        activityService.addItem(activityId, request);
        return Result.ok("添加商品成功", null);
    }

    /**
     * 下架活动商品
     * DELETE /api/activity/{activityId}/items/{itemId}
     */
    @DeleteMapping("/{activityId}/items/{itemId}")
    public Result<Void> removeItem(@PathVariable("activityId") Long activityId,
                                   @PathVariable("itemId") Long itemId) {
        activityService.removeItem(activityId, itemId);
        return Result.ok("下架成功", null);
    }

    /**
     * 删除活动
     * DELETE /api/activity/{id}
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteActivity(@PathVariable("id") Long activityId) {
        activityService.deleteActivity(activityId);
        return Result.ok("删除成功", null);
    }

    /**
     * 获取活动总数（内部调用）
     */
    @GetMapping("/count")
    public Result<Long> getActivityCount() {
        return Result.ok(activityService.getActivityCount());
    }
}
