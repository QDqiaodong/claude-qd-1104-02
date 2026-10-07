package com.bakery.oven.dto;

import java.time.LocalDateTime;

/** 接管预览中的工单卡片：状态、烤盘数、开始时间与风险等级（待烤=中，烘焙中=高） */
public record TakeoverOrderView(
        Long id,
        Long recipeId,
        String recipeName,
        Integer trays,
        String status,
        LocalDateTime startTime,
        String riskLevel,
        String riskText
) {
}
