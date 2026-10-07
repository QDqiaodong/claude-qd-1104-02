package com.bakery.oven.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 排产视图：当前最新版本信息 + 基于最新数据算出的建议顺序与未排入区。
 * stale=true 表示保存版本后烤箱/工单/截止时间等数据已变化，版本已过期，
 * scheduled/unscheduled 为基于最新数据的重算结果，确认后可另存为新版本。
 */
public record ScheduleView(
        Long versionId,
        String versionName,
        String savedBy,
        LocalDateTime savedAt,
        boolean stale,
        String fingerprint,
        List<Long> priorities,
        List<ScheduleItemView> scheduled,
        List<ScheduleUnscheduledView> unscheduled
) {
}
