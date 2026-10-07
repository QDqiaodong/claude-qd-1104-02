package com.bakery.oven.dto;

import java.util.List;

/**
 * 实时预览请求：priorities 为操作员调整后的工单优先级序列（可为空=默认排序）。
 * 只计算不落库；工单的实际状态、烤箱分配与逾期判定全部在服务端完成。
 */
public record SchedulePreviewRequest(
        List<Long> priorities
) {
}
