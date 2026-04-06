package com.flashsale.inventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存变动日志实体
 */
@Data
@TableName("inventory_log")
public class InventoryLog {

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
     * 订单号
     */
    private String orderNo;

    /**
     * 变动类型: 1-扣减, 2-回滚, 3-释放冻结
     */
    private Integer changeType;

    /**
     * 变动数量
     */
    private Integer changeAmount;

    /**
     * 变动前库存
     */
    private Integer beforeStock;

    /**
     * 变动后库存
     */
    private Integer afterStock;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
