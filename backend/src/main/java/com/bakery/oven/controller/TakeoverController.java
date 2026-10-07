package com.bakery.oven.controller;

import com.bakery.oven.common.ApiResponse;
import com.bakery.oven.dto.TakeoverPreview;
import com.bakery.oven.dto.TakeoverRequest;
import com.bakery.oven.dto.TakeoverResult;
import com.bakery.oven.service.TakeoverService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/takeovers")
public class TakeoverController {

    private final TakeoverService takeoverService;

    public TakeoverController(TakeoverService takeoverService) {
        this.takeoverService = takeoverService;
    }

    /** 故障接管预览：该故障炉当前的待烤/烘焙中工单 + 候选替代烤箱及不可选原因 */
    @GetMapping("/ovens/{sourceOvenId}/preview")
    public ApiResponse<TakeoverPreview> preview(@PathVariable Long sourceOvenId) {
        return ApiResponse.success(takeoverService.preview(sourceOvenId));
    }

    /** 执行批量故障接管：整批原子提交，任一工单不满足条件则整批回滚 */
    @PostMapping("/ovens/{sourceOvenId}/take")
    public ApiResponse<TakeoverResult> take(@PathVariable Long sourceOvenId,
                                            @RequestBody TakeoverRequest request) {
        TakeoverResult result = takeoverService.takeover(sourceOvenId, request);
        return ApiResponse.success("接管成功，共迁移 " + result.movedCount() + " 张工单", result);
    }
}
