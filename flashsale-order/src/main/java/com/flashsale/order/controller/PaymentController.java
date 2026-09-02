package com.flashsale.order.controller;

import com.flashsale.common.Result;
import com.flashsale.order.dto.PayRequest;
import com.flashsale.order.service.PaymentService;
import com.flashsale.order.vo.PayResponse;
import com.flashsale.order.vo.PaymentInfoResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 支付控制器
 */
@Slf4j
@RestController
@RequestMapping("/payment")
public class PaymentController {

    @Autowired
    private PaymentService paymentService;

    /**
     * 发起支付
     */
    @PostMapping("/pay")
    public Result<PayResponse> pay(@RequestHeader("X-User-Id") Long userId,
                                   @Valid @RequestBody PayRequest request) {
        log.info("支付请求，userId={}, orderNo={}, payMethod={}", userId, request.getOrderNo(), request.getPayMethod());
        PayResponse response = paymentService.initiatePayment(userId, request.getOrderNo(), request.getPayMethod());
        return Result.ok(response);
    }

    /**
     * 查询支付信息
     */
    @GetMapping("/{orderNo}")
    public Result<PaymentInfoResponse> getPaymentInfo(@RequestHeader("X-User-Id") Long userId,
                                                       @PathVariable("orderNo") String orderNo) {
        PaymentInfoResponse response = paymentService.getPaymentInfo(userId, orderNo);
        return Result.ok(response);
    }
}
