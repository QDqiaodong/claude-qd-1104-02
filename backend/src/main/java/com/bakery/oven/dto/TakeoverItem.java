package com.bakery.oven.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 接管明细：工单 #orderId 迁到烤箱 #targetOvenId */
public record TakeoverItem(
        Long orderId,
        Long targetOvenId
) {
    @JsonCreator
    public TakeoverItem(@JsonProperty("orderId") Long orderId,
                        @JsonProperty("targetOvenId") Long targetOvenId) {
        this.orderId = orderId;
        this.targetOvenId = targetOvenId;
    }
}
