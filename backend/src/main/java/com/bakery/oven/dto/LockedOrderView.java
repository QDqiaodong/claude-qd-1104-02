package com.bakery.oven.dto;

/** 已开始或已完成的工单：只读展示，锁定不可拖动、不参与排产 */
public record LockedOrderView(
        Long orderId,
        String status,
        String recipeName,
        Integer trays,
        String ovenCode,
        String startTime
) {
}
