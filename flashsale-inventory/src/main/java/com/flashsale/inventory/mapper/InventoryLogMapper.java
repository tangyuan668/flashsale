package com.flashsale.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashsale.inventory.entity.InventoryLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存日志Mapper
 */
@Mapper
public interface InventoryLogMapper extends BaseMapper<InventoryLog> {

    /**
     * 查询待补偿的库存扣减记录
     * 条件：变动类型为扣减(1)，且创建时间早于指定时间，且没有被回滚过
     *
     * @param beforeTime 时间阈值，查询创建时间早于此时间的记录
     * @param limit      限制查询数量
     * @return 待补偿的库存扣减记录列表
     */
    @Select("SELECT * FROM inventory_log " +
            "WHERE change_type = 1 " +
            "AND create_time < #{beforeTime} " +
            "AND order_no IS NOT NULL " +
            "AND NOT EXISTS (" +
            "    SELECT 1 FROM inventory_log " +
            "    WHERE order_no = inventory_log.order_no " +
            "    AND change_type = 2" +
            ") " +
            "ORDER BY create_time ASC " +
            "LIMIT #{limit}")
    List<InventoryLog> selectPendingCompensation(@Param("beforeTime") LocalDateTime beforeTime,
                                                   @Param("limit") int limit);
}
