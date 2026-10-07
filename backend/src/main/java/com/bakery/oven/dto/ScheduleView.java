package com.bakery.oven.dto;

import java.util.List;

/**
 * 排产视图：一份建议顺序的完整展示。
 * saved=false 表示基于最新数据的实时预览（尚未落库）；historical=true 表示查看历史版本（只读）。
 * stale=true 时 staleReasons 逐条列出输入数据的变化（烤箱状态/烤盘数/交付截止等），提示重算。
 */
public record ScheduleView(
        boolean saved,
        boolean historical,
        Long versionId,
        Integer versionNo,
        Integer baseVersionNo,
        Integer latestVersionNo,
        String operatorName,
        String createdAt,
        boolean stale,
        List<String> staleReasons,
        List<ScheduledItemView> scheduled,
        List<UnscheduledItemView> unscheduled,
        List<LockedOrderView> locked,
        List<ScheduleOvenView> ovens,
        int pendingCount
) {
}
