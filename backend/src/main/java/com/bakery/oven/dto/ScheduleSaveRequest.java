package com.bakery.oven.dto;

import java.util.List;

/**
 * 保存排产版本请求。
 * baseVersionId + fingerprint 双重校验：
 *  - baseVersionId 与当前最新版本不一致 → 他人已保存更新的版本（并发保存只保留一个）；
 *  - fingerprint 与当前数据不一致 → 排产基于旧数据（较慢的旧计算不能覆盖新版本）。
 */
public record ScheduleSaveRequest(
        String name,
        String operator,
        Long baseVersionId,
        String fingerprint,
        List<Long> priorities
) {
}
