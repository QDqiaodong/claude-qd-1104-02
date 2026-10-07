package com.bakery.oven.dto;

import java.util.List;

/** 排产预览请求：按给定的优先级顺序（工单 id 列表）试算，不落库 */
public record SchedulePreviewRequest(
        List<Long> priorities
) {
}
