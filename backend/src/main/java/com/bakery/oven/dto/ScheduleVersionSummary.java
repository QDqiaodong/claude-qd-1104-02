package com.bakery.oven.dto;

/** 排产版本历史列表项 */
public record ScheduleVersionSummary(
        Long id,
        Integer versionNo,
        Integer baseVersionNo,
        String operatorName,
        Integer scheduledCount,
        Integer unscheduledCount,
        String createdAt,
        boolean current
) {
}
