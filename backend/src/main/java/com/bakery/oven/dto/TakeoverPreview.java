package com.bakery.oven.dto;

import java.util.List;

/**
 * 接管预览：故障炉当前可接管的工单（待烤 / 烘焙中）与全部候选替代烤箱，
 * 并附每炉能否被该工单选用的原因，供前端渲染选择器与风险提示。
 * 预览只做读校验，真正的裁决在接管事务内重新执行。
 */
public record TakeoverPreview(
        Long sourceOvenId,
        String sourceOvenCode,
        String sourceOvenStatus,
        List<TakeoverOrderView> orders,
        List<TakeoverOvenView> targetOvens
) {
}
