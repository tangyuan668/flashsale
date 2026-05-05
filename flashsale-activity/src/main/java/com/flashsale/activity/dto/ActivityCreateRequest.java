package com.flashsale.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建活动请求
 */
@Data
public class ActivityCreateRequest {

    /**
     * 活动名称
     */
    @NotBlank(message = "活动名称不能为空")
    private String name;

    /**
     * 活动描述
     */
    private String description;

    /**
     * 封面图URL
     */
    private String coverImage;

    /**
     * 开始时间
     */
    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    /**
     * 活动商品列表
     */
    private List<ActivityItemRequest> items;

    @Data
    public static class ActivityItemRequest {
        /**
         * 商品ID
         */
        private Long itemId;

        /**
         * 商品名称
         */
        private String itemName;

        /**
         * 商品图片
         */
        private String itemImage;

        /**
         * 原价
         */
        private Double originalPrice;

        /**
         * 秒杀价
         */
        private Double seckillPrice;

        /**
         * 库存数量
         */
        private Integer stock;

        /**
         * 每人限购数量
         */
        private Integer limitPerUser;

        /**
         * 排序序号
         */
        private Integer sortOrder;
    }
}
