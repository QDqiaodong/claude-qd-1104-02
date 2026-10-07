package com.bakery.oven.dto;

/**
 * 已排入的建议项：顺序、预计开始/完成、占用烤箱、容量说明、截止风险与可解释原因。
 * 时间均为 yyyy-MM-dd HH:mm:ss 字符串（后端格式化，前端只展示不推算）。
 */
public record ScheduledItemView(
        Integer seq,
        Long orderId,
        Long recipeId,
        String recipeName,
        Integer trays,
        Integer bakeMinutes,
        String dueTime,
        Long ovenId,
        String ovenCode,
        Integer ovenCapacity,
        String estStartTime,
        String estFinishTime,
        String deadlineRisk,
        String capacityNote,
        String reason,
        Long originOvenId,
        String originOvenCode,
        String originOvenStatus
) {
}
