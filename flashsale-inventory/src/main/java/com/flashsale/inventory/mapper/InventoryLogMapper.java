package com.flashsale.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashsale.inventory.entity.InventoryLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 库存日志Mapper
 */
@Mapper
public interface InventoryLogMapper extends BaseMapper<InventoryLog> {
}
