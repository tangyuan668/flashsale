package com.flashsale.inventory.controller;

import com.flashsale.common.Result;
import com.flashsale.common.dto.StockDeductRequest;
import com.flashsale.inventory.service.InventoryService;
import com.flashsale.inventory.vo.InventoryInfoResponse;
import com.flashsale.inventory.vo.StockDeductResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 库存控制器
 */
@Slf4j
@RestController
@RequestMapping("/inventory")
public class InventoryController {

    @Autowired
    private InventoryService inventoryService;

    /**
     * 获取库存信息
     * GET /api/inventory?activityId={activityId}&itemId={itemId}
     */
    @GetMapping
    public Result<InventoryInfoResponse> getInventory(@RequestParam("activityId") Long activityId,
                                                       @RequestParam("itemId") Long itemId) {
        InventoryInfoResponse response = inventoryService.getInventory(activityId, itemId);
        return Result.ok(response);
    }

    /**
     * 扣减库存（Redis+Lua原子操作）
     * POST /api/inventory/deduct
     */
    @PostMapping("/deduct")
    public Result<StockDeductResponse> deductStock(@Valid @RequestBody StockDeductRequest request) {
        StockDeductResponse response = inventoryService.deductStock(request);
        if (Boolean.TRUE.equals(response.getSuccess())) {
            return Result.ok("扣减成功", response);
        } else {
            return Result.fail(response.getMessage());
        }
    }

    /**
     * 回滚库存
     * POST /api/inventory/rollback
     */
    @PostMapping("/rollback")
    public Result<Void> rollbackStock(@RequestParam("orderNo") String orderNo,
                                       @RequestParam("activityId") Long activityId,
                                       @RequestParam("itemId") Long itemId,
                                       @RequestParam("quantity") Integer quantity,
                                       @RequestParam("userId") Long userId) {
        inventoryService.rollbackStock(orderNo, activityId, itemId, quantity, userId);
        return Result.ok("回滚成功", null);
    }
}
