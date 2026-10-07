package com.bakery.oven.dto;

import java.util.List;

/**
 * 一次故障接管请求：把故障烤箱上的多张工单批量迁到替代烤箱。
 * items 中每张工单单独指定目标烤箱；整批为一个事务，任一张不满足条件整批回滚。
 */
public record TakeoverRequest(
        List<TakeoverItem> items
) {
}
