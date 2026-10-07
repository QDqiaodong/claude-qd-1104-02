package com.bakery.oven.dto;

/**
 * 未排入的工单：没有可用烤箱、容量不足或预计逾期的工单留在未排入区，
 * reasonType 供前端展示容量预警等标签，reason 为完整原因说明，不伪装成已安排。
 */
public record UnscheduledItemView(
        Long orderId,
        Long recipeId,
        String recipeName,
        Integer trays,
        Integer bakeMinutes,
        String dueTime,
        String reasonType,
        String reason,
        Long originOvenId,
        String originOvenCode,
        String originOvenStatus
) {
}
