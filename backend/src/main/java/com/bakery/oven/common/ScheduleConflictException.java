package com.bakery.oven.common;

/**
 * 排产保存冲突：保存时基于的版本号已落后于库内最新版本
 * （两名操作员同时保存同一排产，或较慢的旧计算晚到），
 * 只有最新版本能被保留，失败方需刷新后基于最新顺序重新调整。
 */
public class ScheduleConflictException extends RuntimeException {

    private final int latestVersionNo;

    public ScheduleConflictException(int latestVersionNo, Integer baseVersionNo) {
        super("保存失败：排产已被他人更新为版本 #" + latestVersionNo
                + "，你基于 " + (baseVersionNo == null ? "未保存的预览" : "版本 #" + baseVersionNo)
                + " 的修改未保存。请查看最新顺序与冲突提示后重新调整。");
        this.latestVersionNo = latestVersionNo;
    }

    public int getLatestVersionNo() {
        return latestVersionNo;
    }
}
