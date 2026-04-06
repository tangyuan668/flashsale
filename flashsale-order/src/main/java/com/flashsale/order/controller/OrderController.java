package com.flashsale.order.controller;

import com.flashsale.common.Result;
import com.flashsale.order.dto.SeckillRequest;
import com.flashsale.order.service.OrderService;
import com.flashsale.order.vo.OrderDetailResponse;
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
        if (Boolean.TRUE.equals(response.getSuccess())) {
            return Result.ok("下单成功，请尽快支付", response);
        } else {
            return Result.fail(response.getMessage());
        }
    }

    /**
     * 获取订单详情
     * GET /api/order/{orderNo}
     */
    @GetMapping("/{orderNo}")
    public Result<OrderDetailResponse> getOrderDetail(@PathVariable("orderNo") String orderNo) {
        OrderDetailResponse response = orderService.getOrderDetail(orderNo);
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
     * 取消订单
     * POST /api/order/{orderNo}/cancel
     */
    @PostMapping("/{orderNo}/cancel")
    public Result<Void> cancelOrder(@PathVariable("orderNo") String orderNo,
                                      @RequestParam(value = "reason", required = false) String reason) {
        orderService.cancelOrder(orderNo, reason != null ? reason : "用户取消");
        return Result.ok("取消成功", null);
    }
}
