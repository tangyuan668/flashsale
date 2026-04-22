package com.flashsale.order.feign;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动信息DTO（从Activity服务获取）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivityDto {

    private Long id;
    private String name;
    private String description;
    private String coverImage;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;
    private String statusDesc;
    private Integer preheatStatus;
    private List<ActivityItemDto> items;
    private LocalDateTime createTime;

    /**
     * 活动商品信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityItemDto {
        private Long id;
        private Long activityId;
        private Long itemId;
        private String itemName;
        private String itemImage;
        private BigDecimal originalPrice;
        private BigDecimal seckillPrice;
        private Integer stock;
        private Integer limitPerUser;
        private Integer sortOrder;
    }
}
