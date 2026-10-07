package com.bakery.oven.dto;

/**
 * 排产视图里的烤箱容量面板：当前状态、是否可排、预计可排时间与说明，
 * 以及当前版本里该炉被建议排入的工单数（跨烤箱容量一目了然）。
 */
public record ScheduleOvenView(
        Long id,
        String code,
        Integer capacity,
        String status,
        boolean schedulable,
        String availableFrom,
        String note,
        int assignedCount
) {
}
