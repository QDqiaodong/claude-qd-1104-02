package com.bakery.oven.controller;

import com.bakery.oven.common.ApiResponse;
import com.bakery.oven.dto.SchedulePreviewRequest;
import com.bakery.oven.dto.ScheduleSaveRequest;
import com.bakery.oven.dto.ScheduleView;
import com.bakery.oven.service.ScheduleService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /** 当前排产视图：最新版本 + 基于最新数据的重算结果（含过期标记） */
    @GetMapping("/current")
    public ApiResponse<ScheduleView> current() {
        return ApiResponse.success(scheduleService.current());
    }

    /** 试算：按调整后的优先级重算，不落库 */
    @PostMapping("/preview")
    public ApiResponse<ScheduleView> preview(@RequestBody SchedulePreviewRequest request) {
        return ApiResponse.success(scheduleService.preview(request == null ? null : request.priorities()));
    }

    /** 保存排产版本：并发只保留一个，基于旧数据/旧版本的保存会被拒绝 */
    @PostMapping("/versions")
    public ApiResponse<ScheduleView> save(@RequestBody ScheduleSaveRequest request) {
        return ApiResponse.success(scheduleService.save(request));
    }
}
