package com.flashsale.order.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flashsale.common.Result;
import com.flashsale.order.entity.OrderInfo;
import com.flashsale.order.feign.ActivityDto;
import com.flashsale.order.feign.ActivityFeignClient;
import com.flashsale.order.feign.UserFeignClient;
import com.flashsale.order.mapper.OrderInfoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 统计控制器
 */
@Slf4j
@RestController
@RequestMapping("/stat")
public class StatController {

    @Autowired
    private OrderInfoMapper orderInfoMapper;

    @Autowired
    private UserFeignClient userFeignClient;

    @Autowired
    private ActivityFeignClient activityFeignClient;

    /**
     * 总览统计
     * GET /api/stat/overview
     */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        Map<String, Object> data = new HashMap<>();

        // 用户总数
        Long userCount = 0L;
        try {
            Result<Long> userResult = userFeignClient.getUserCount();
            if (userResult != null && userResult.getData() != null) {
                userCount = userResult.getData();
            }
        } catch (Exception e) {
            log.warn("获取用户总数失败: {}", e.getMessage());
        }

        // 活动总数
        Long activityCount = 0L;
        try {
            Result<Long> activityResult = activityFeignClient.getActivityCount();
            if (activityResult != null && activityResult.getData() != null) {
                activityCount = activityResult.getData();
            }
        } catch (Exception e) {
            log.warn("获取活动总数失败: {}", e.getMessage());
        }

        // 订单总数
        Long orderCount = orderInfoMapper.selectCount(new LambdaQueryWrapper<>());

        // 总销售额（已支付的订单）
        List<OrderInfo> paidOrders = orderInfoMapper.selectList(
                new LambdaQueryWrapper<OrderInfo>().eq(OrderInfo::getStatus, 1)
        );
        BigDecimal totalSales = paidOrders.stream()
                .map(OrderInfo::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        data.put("userCount", userCount);
        data.put("activityCount", activityCount);
        data.put("orderCount", orderCount);
        data.put("totalSales", totalSales);

        return Result.ok(data);
    }

    /**
     * 订单趋势
     * GET /api/stat/order-trend?days=7
     */
    @GetMapping("/order-trend")
    public Result<Map<String, Object>> orderTrend(
            @RequestParam(value = "days", defaultValue = "7") int days) {
        List<Map<String, Object>> trend = orderInfoMapper.selectOrderTrend(days);

        List<String> dates = new ArrayList<>();
        List<Long> orderCounts = new ArrayList<>();
        List<BigDecimal> salesAmounts = new ArrayList<>();

        // 填充缺失的日期
        Map<String, Map<String, Object>> trendMap = trend.stream()
                .collect(Collectors.toMap(
                        m -> m.get("date").toString(),
                        m -> m
                ));

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            String dateStr = date.toString();
            String displayDate = date.format(formatter);

            dates.add(displayDate);
            Map<String, Object> dayData = trendMap.get(dateStr);
            if (dayData != null) {
                orderCounts.add(((Number) dayData.get("orderCount")).longValue());
                salesAmounts.add(new BigDecimal(dayData.get("salesAmount").toString()));
            } else {
                orderCounts.add(0L);
                salesAmounts.add(BigDecimal.ZERO);
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("dates", dates);
        data.put("orderCounts", orderCounts);
        data.put("salesAmounts", salesAmounts);

        return Result.ok(data);
    }

    /**
     * 活动销量排行
     * GET /api/stat/activity-rank?limit=10
     */
    @GetMapping("/activity-rank")
    public Result<Map<String, Object>> activityRank(
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        List<Map<String, Object>> rank = orderInfoMapper.selectActivityRank(limit);

        // 查询活动名称，已删除的活动不展示
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rank) {
            Long activityId = ((Number) row.get("activity_id")).longValue();
            try {
                Result<ActivityDto> actResult = activityFeignClient.getActivityDetail(activityId);
                if (actResult == null || actResult.getData() == null || actResult.getData().getName() == null) {
                    continue; // 活动已删除，跳过
                }

                Map<String, Object> item = new HashMap<>();
                item.put("name", actResult.getData().getName());
                item.put("salesCount", ((Number) row.get("salesCount")).longValue());
                items.add(item);
            } catch (Exception e) {
                log.debug("获取活动名称失败，跳过: activityId={}", activityId);
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("items", items);
        return Result.ok(data);
    }

    /**
     * 订单状态分布
     * GET /api/stat/order-status-distribution
     */
    @GetMapping("/order-status-distribution")
    public Result<Map<String, Object>> orderStatusDistribution() {
        List<Map<String, Object>> distribution = orderInfoMapper.selectStatusDistribution();

        Map<String, String> statusDesc = Map.of(
                "0", "待支付",
                "1", "已支付",
                "2", "已取消",
                "3", "已超时",
                "4", "已失效"
        );

        List<Map<String, Object>> items = distribution.stream().map(row -> {
            Map<String, Object> item = new HashMap<>();
            String status = String.valueOf(row.get("status"));
            item.put("status", status);
            item.put("statusDesc", statusDesc.getOrDefault(status, "未知"));
            item.put("count", ((Number) row.get("count")).longValue());
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("items", items);
        return Result.ok(data);
    }

    /**
     * 每小时订单分布
     * GET /api/stat/hourly-distribution
     */
    @GetMapping("/hourly-distribution")
    public Result<Map<String, Object>> hourlyDistribution(
            @RequestParam(value = "days", defaultValue = "7") int days) {
        List<Map<String, Object>> hourly = orderInfoMapper.selectHourlyDistribution(days);

        // 初始化 0-23 小时
        Map<Integer, Long> hourMap = new LinkedHashMap<>();
        for (int i = 0; i < 24; i++) {
            hourMap.put(i, 0L);
        }
        for (Map<String, Object> row : hourly) {
            int hour = ((Number) row.get("hour")).intValue();
            long count = ((Number) row.get("count")).longValue();
            hourMap.put(hour, count);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("hours", new ArrayList<>(hourMap.keySet()));
        data.put("counts", new ArrayList<>(hourMap.values()));
        return Result.ok(data);
    }
}
