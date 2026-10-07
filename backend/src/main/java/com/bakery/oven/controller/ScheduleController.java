package com.bakery.oven.controller;

import com.bakery.oven.common.ApiResponse;
import com.bakery.oven.dto.SchedulePreviewRequest;
import com.bakery.oven.dto.ScheduleSaveRequest;
import com.bakery.oven.dto.ScheduleVersionSummary;
import com.bakery.oven.dto.ScheduleView;
import com.bakery.oven.service.ScheduleService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /** 当前排产：有已保存版本返回最新版本（含过期判定），否则返回基于最新数据的未保存预览 */
    @GetMapping("/current")
    public ApiResponse<ScheduleView> current() {
        return ApiResponse.success(scheduleService.current());
    }

    /** 实时预览：按给定优先级基于最新数据计算，不落库（重算 / 拖动后即时刷新） */
    @PostMapping("/preview")
    public ApiResponse<ScheduleView> preview(@RequestBody(required = false) SchedulePreviewRequest req) {
        return ApiResponse.success(scheduleService.preview(req));
    }

    /** 保存排产版本：行锁串行 + 基于版本号乐观校验，并发保存只保留一个版本 */
    @PostMapping("/versions")
    public ApiResponse<ScheduleView> save(@RequestBody(required = false) ScheduleSaveRequest req) {
        ScheduleView view = scheduleService.save(req);
        return ApiResponse.success("已保存排产版本 #" + view.versionNo(), view);
    }

    /** 版本历史（新→旧） */
    @GetMapping("/versions")
    public ApiResponse<List<ScheduleVersionSummary>> versions() {
        return ApiResponse.success(scheduleService.versions());
    }

    /** 查看指定版本（历史版本只读展示） */
    @GetMapping("/versions/{id}")
    public ApiResponse<ScheduleView> version(@PathVariable Long id) {
        return ApiResponse.success(scheduleService.version(id));
    }
}
