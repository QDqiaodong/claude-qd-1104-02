package com.bakery.oven.dto;

import java.time.LocalDateTime;

/**
 * 未排入区的工单：没有可用烤箱 / 容量不足 / 预计逾期等原因，
 * 必须如实展示，不能伪装成已安排。
 */
public record ScheduleUnscheduledView(
        Long orderId,
        String recipeName,
        Integer trays,
        LocalDateTime deadline,
        Long ownOvenId,
        String ownOvenCode,
        String reasonType,
        String reason
) {
}
