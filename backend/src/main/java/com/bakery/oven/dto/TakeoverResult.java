package com.bakery.oven.dto;

import java.util.List;

/** 接管成功后的落库结果：迁移成功的工单明细（新炉号、保留的状态/开始时间） */
public record TakeoverResult(
        Long sourceOvenId,
        String sourceOvenCode,
        int movedCount,
        List<MovedOrderView> moved
) {

    public record MovedOrderView(
            Long orderId,
            String status,
            Integer trays,
            Long targetOvenId,
            String targetOvenCode,
            String startTime
    ) {
    }
}
