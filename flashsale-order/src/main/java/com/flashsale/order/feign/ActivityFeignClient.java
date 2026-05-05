package com.flashsale.order.feign;

import com.flashsale.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Activity服务Feign客户端
 */
@FeignClient(name = "flashsale-activity", path = "/activity")
public interface ActivityFeignClient {

    /**
     * 获取活动详情
     * @param id 活动ID
     * @return 活动信息
     */
    @GetMapping("/{id}")
    Result<ActivityDto> getActivityDetail(@PathVariable("id") Long id);
}
