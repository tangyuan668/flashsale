package com.flashsale.inventory.feign;

import com.flashsale.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 订单服务Feign客户端
 */
@FeignClient(name = "flashsale-order", path = "/order/internal")
public interface OrderFeignClient {

    /**
     * 检查订单是否存在（内部接口）
     *
     * @param orderNo 订单号
     * @return 订单是否存在
     */
    @GetMapping("/exists")
    Result<Boolean> checkOrderExists(@RequestParam("orderNo") String orderNo);

    /**
     * 检查用户对指定商品是否有成功的订单（排除指定订单号）
     *
     * @param userId 用户ID
     * @param activityId 活动ID
     * @param itemId 商品ID
     * @param excludeOrderNo 排除的订单号（当前检查的失败订单）
     * @return 是否有成功订单
     */
    @GetMapping("/hasSuccessOrder")
    Result<Boolean> checkUserHasSuccessOrder(
            @RequestParam("userId") Long userId,
            @RequestParam("activityId") Long activityId,
            @RequestParam("itemId") Long itemId,
            @RequestParam("excludeOrderNo") String excludeOrderNo
    );
}
