package com.flashsale.order.feign;

import com.flashsale.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * User服务Feign客户端
 */
@FeignClient(name = "flashsale-user", path = "/user")
public interface UserFeignClient {

    @GetMapping("/count")
    Result<Long> getUserCount();
}
