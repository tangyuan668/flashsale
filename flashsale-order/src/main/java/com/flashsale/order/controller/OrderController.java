package com.flashsale.order.controller;

import com.flashsale.common.Result;
import com.flashsale.order.dto.SeckillRequest;
import com.flashsale.order.service.OrderService;
import com.flashsale.order.vo.OrderDetailResponse;
import com.flashsale.order.vo.OrderStatusResponse;
import com.flashsale.order.vo.SeckillResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 订单控制器
 */
@Slf4j
@RestController
@RequestMapping("/order")
public class OrderController {

    @Autowired
    private OrderService orderService;

    /**
     * 秒杀下单
     * POST /api/order/create
     */
    @PostMapping("/create")
    public Result<SeckillResponse> seckill(@RequestHeader("X-User-Id") Long userId,
                                             @Valid @RequestBody SeckillRequest request) {
        SeckillResponse response = orderService.seckill(userId, request);
        // 成功或处理中都返回成功，前端根据 success 字段判断是否需要轮询
        if (Boolean.TRUE.equals(response.getSuccess()) || response.getSuccess() == null) {
            return Result.ok("下单已提交，请查询结果", response);
        } else {
            return Result.fail(response.getMessage());
        }
    }

    /**
     * 获取订单详情
     * GET /api/order/{orderNo}
     */
    @GetMapping("/{orderNo}")
    public Result<OrderDetailResponse> getOrderDetail(@RequestHeader("X-User-Id") Long userId,
                                                       @PathVariable("orderNo") String orderNo) {
        OrderDetailResponse response = orderService.getOrderDetail(orderNo, userId);
        return Result.ok(response);
    }

    /**
     * 获取订单状态（用于轮询）
     * GET /api/order/{orderNo}/status
     */
    @GetMapping("/{orderNo}/status")
    public Result<OrderStatusResponse> getOrderStatus(@PathVariable("orderNo") String orderNo) {
        OrderStatusResponse response = orderService.getOrderStatus(orderNo);
        return Result.ok(response);
    }

    /**
     * 获取我的订单列表
     * GET /api/order/my
     */
    @GetMapping("/my")
    public Result<List<OrderDetailResponse>> getMyOrders(@RequestHeader("X-User-Id") Long userId) {
        List<OrderDetailResponse> list = orderService.getMyOrders(userId);
        return Result.ok(list);
    }

    /**
     * 获取所有订单列表（管理员）
     * GET /api/order/all
     */
    @GetMapping("/all")
    public Result<List<OrderDetailResponse>> getAllOrders() {
        List<OrderDetailResponse> list = orderService.getAllOrders();
        return Result.ok(list);
    }

    /**
     * 取消订单
     * POST /api/order/{orderNo}/cancel
     */
    @PostMapping("/{orderNo}/cancel")
    public Result<Void> cancelOrder(@RequestHeader("X-User-Id") Long userId,
                                      @PathVariable("orderNo") String orderNo,
                                      @RequestParam(value = "reason", required = false) String reason) {
        orderService.cancelOrder(userId, orderNo, reason != null ? reason : "用户取消");
        return Result.ok("取消成功", null);
    }

}
