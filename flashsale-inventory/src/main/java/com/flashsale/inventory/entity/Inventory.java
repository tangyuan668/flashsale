package com.flashsale.inventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存实体
 */
@Data
@TableName("inventory")
public class Inventory {

    /**
     * ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 活动ID
     */
    private Long activityId;

    /**
     * 商品ID
     */
    private Long itemId;

    /**
     * 总库存
     */
    private Integer totalStock;

    /**
     * 可用库存
     */
    private Integer availableStock;

    /**
     * 冻结库存(已下单未支付)
     */
    private Integer frozenStock;

    /**
     * 乐观锁版本号
     */
    private Integer version;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除: 1-已删除, 0-未删除
     */
    @TableLogic
    private Integer deleted;
}
