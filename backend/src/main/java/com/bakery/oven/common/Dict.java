package com.bakery.oven.common;

import java.util.List;

/**
 * 烘焙坊业务词典：烤箱状态、面包配方状态、烘焙工单状态机与流转、
 * 出货状态与渠道。规则集中在此，Service 引用做校验与状态机推进。
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 烤箱 ----------------
    /** 空闲 / 占用 / 故障 */
    public static final List<String> OVEN_STATUS = List.of("空闲", "占用", "故障");

    // ---------------- 面包配方 ----------------
    /** 上架 / 隐藏 */
    public static final List<String> RECIPE_STATUS = List.of("上架", "隐藏");

    // ---------------- 烘焙工单 ----------------
    /** 待烤 → 烘焙中 → 已出炉 → 已质检，逐级推进，不可跳级 */
    public static final List<String> BAKE_ORDER_FLOW = List.of("待烤", "烘焙中", "已出炉", "已质检");

    public static final List<String> BAKE_ORDER_STATUS = List.of("待烤", "烘焙中", "已出炉", "已质检");

    // ---------------- 出货 ----------------
    /** 待发 / 已发 */
    public static final List<String> SHIPMENT_STATUS = List.of("待发", "已发");

    /** 门店 / 外卖 */
    public static final List<String> SHIPMENT_CHANNEL = List.of("门店", "外卖");

    // ---------------- 校验方法 ----------------

    public static boolean isValidOvenStatus(String status) {
        return status != null && OVEN_STATUS.contains(status);
    }

    public static boolean isValidRecipeStatus(String status) {
        return status != null && RECIPE_STATUS.contains(status);
    }

    public static boolean isValidBakeOrderStatus(String status) {
        return status != null && BAKE_ORDER_STATUS.contains(status);
    }

    /** 烘焙工单状态机：只能逐级推进（待烤→烘焙中→已出炉→已质检） */
    public static boolean canTransferBakeOrder(String from, String to) {
        int i = BAKE_ORDER_FLOW.indexOf(from);
        return i >= 0 && i + 1 < BAKE_ORDER_FLOW.size() && BAKE_ORDER_FLOW.get(i + 1).equals(to);
    }

    public static boolean isValidShipmentStatus(String status) {
        return status != null && SHIPMENT_STATUS.contains(status);
    }

    public static boolean isValidShipmentChannel(String channel) {
        return channel != null && SHIPMENT_CHANNEL.contains(channel);
    }
}
