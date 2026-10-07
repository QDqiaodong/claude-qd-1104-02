package com.bakery.oven.dto;

/**
 * 接管预览中的候选替代烤箱。eligible=false 时 reason 给出不能选的原因，
 * 前端置灰展示；注意烘焙中工单与待烤工单的资格口径不同（烘焙槽位约束）。
 */
public record TakeoverOvenView(
        Long id,
        String code,
        Integer capacity,
        String status,
        boolean eligible,
        String reason
) {
}
