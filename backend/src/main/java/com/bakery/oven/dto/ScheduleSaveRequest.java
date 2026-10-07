package com.bakery.oven.dto;

import java.util.List;

/**
 * 保存排产版本请求。
 * baseVersionNo：保存时基于的版本号（乐观并发校验；首个版本传 null）；
 * priorities：操作员调整后的优先级序列（可为空=默认排序），服务端基于最新数据重算后落库。
 */
public record ScheduleSaveRequest(
        Long baseVersionNo,
        String operatorName,
        List<Long> priorities
) {
}
