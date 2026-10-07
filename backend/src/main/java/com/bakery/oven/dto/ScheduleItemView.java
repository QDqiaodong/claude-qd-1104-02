package com.bakery.oven.dto;

import java.time.LocalDateTime;

/**
 * 一条已排入的建议：预计开始/完成时间、建议占用烤箱、容量占用与截止时间风险。
 * 均为建议信息，不改写工单实际状态与故障接管归属。
 */
public record ScheduleItemView(
        Long orderId,
        String recipeName,
        Integer trays,
        LocalDateTime deadline,
        Long ovenId,
        String ovenCode,
        Integer ovenCapacity,
        Long ownOvenId,
        String ownOvenCode,
        String ownOvenStatus,
        LocalDateTime estStart,
        LocalDateTime estFinish,
        String risk,
        String note
) {
}
