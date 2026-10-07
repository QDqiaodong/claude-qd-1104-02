package com.bakery.oven.service;

import com.bakery.oven.common.BizException;
import com.bakery.oven.dto.TakeoverItem;
import com.bakery.oven.dto.TakeoverOrderView;
import com.bakery.oven.dto.TakeoverOvenView;
import com.bakery.oven.dto.TakeoverPreview;
import com.bakery.oven.dto.TakeoverRequest;
import com.bakery.oven.dto.TakeoverResult;
import com.bakery.oven.entity.BakeOrder;
import com.bakery.oven.entity.BreadRecipe;
import com.bakery.oven.entity.Oven;
import com.bakery.oven.repository.BakeOrderRepository;
import com.bakery.oven.repository.BreadRecipeRepository;
import com.bakery.oven.repository.OvenRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 故障接管：把故障烤箱上的「待烤 / 烘焙中」工单批量迁到替代烤箱。
 * 约束：
 *  - 仅故障炉可发起接管；
 *  - 替代烤箱必须空闲、容量 ≥ 工单实际烤盘数；
 *  - 替代烤箱不能再接收第二张烘焙中工单（库内已有或本批重复分配都不行）；
 *  - 待烤迁移后仍为待烤；烘焙中迁移后保留状态与原开始时间；
 *  - 一次接管可迁多张，任一张不满足条件整批不落库（事务回滚 + 逐单原因）；
 *  - 同一替代烤箱被两批接管同时选中时，依赖 InnoDB 行锁串行裁决，
 *    只有一批能拿到空闲炉并成功，失败批整批回滚，不留半迁移数据。
 */
@Service
public class TakeoverService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OvenRepository ovenRepository;
    private final BakeOrderRepository orderRepository;
    private final BreadRecipeRepository recipeRepository;

    public TakeoverService(OvenRepository ovenRepository,
                           BakeOrderRepository orderRepository,
                           BreadRecipeRepository recipeRepository) {
        this.ovenRepository = ovenRepository;
        this.orderRepository = orderRepository;
        this.recipeRepository = recipeRepository;
    }

    /** 接管预览：故障炉上可迁的工单 + 全部候选替代烤箱（只读，不作裁决） */
    public TakeoverPreview preview(Long sourceOvenId) {
        Oven source = ovenRepository.findById(sourceOvenId)
                .orElseThrow(() -> new BizException("烤箱不存在：" + sourceOvenId));
        if (!"故障".equals(source.status)) {
            throw new BizException("只有故障烤箱才能发起接管，当前状态为：" + source.status);
        }

        Map<Long, String> recipeNames = recipeRepository.findAll().stream()
                .collect(Collectors.toMap(r -> r.id, r -> r.name));

        List<TakeoverOrderView> orderViews = orderRepository.findByOvenId(source.id).stream()
                .filter(o -> "待烤".equals(o.status) || "烘焙中".equals(o.status))
                .map(o -> {
                    String name = recipeNames.getOrDefault(o.recipeId, "?");
                    boolean baking = "烘焙中".equals(o.status);
                    String riskText = baking
                            ? "高风险：烘焙中断后换炉续烤，沿用原开始时间（"
                                    + (o.startTime == null ? "-" : FMT.format(o.startTime))
                                    + "），请确认替代炉已完成预热、炉温一致"
                            : "中风险：尚未开烤，迁移不影响开始时间，到替代炉后重新开始烘焙即可";
                    return new TakeoverOrderView(o.id, o.recipeId, name, o.trays,
                            o.status, o.startTime, baking ? "high" : "medium", riskText);
                })
                .toList();

        // 已有烘焙中工单的炉子（占用来源），用于提示“不能再接收第二张烘焙中工单”
        Set<Long> bakingOvenIds = orderRepository.findByStatus("烘焙中").stream()
                .map(o -> o.ovenId)
                .collect(Collectors.toSet());

        List<TakeoverOvenView> ovenViews = ovenRepository.findAll().stream()
                .sorted((a, b) -> a.id.compareTo(b.id))
                .map(o -> {
                    if (Objects.equals(o.id, source.id)) {
                        return new TakeoverOvenView(o.id, o.code, o.capacity, o.status,
                                false, "故障烤箱本身不能作为替代烤箱");
                    }
                    if ("故障".equals(o.status)) {
                        return new TakeoverOvenView(o.id, o.code, o.capacity, o.status,
                                false, "该烤箱处于故障状态");
                    }
                    if (bakingOvenIds.contains(o.id)) {
                        return new TakeoverOvenView(o.id, o.code, o.capacity, o.status,
                                false, "已有一张烘焙中工单，不能再接收第二张烘焙中工单");
                    }
                    if (!"空闲".equals(o.status)) {
                        return new TakeoverOvenView(o.id, o.code, o.capacity, o.status,
                                false, "替代烤箱必须处于空闲，当前为：" + o.status);
                    }
                    return new TakeoverOvenView(o.id, o.code, o.capacity, o.status, true, null);
                })
                .toList();

        return new TakeoverPreview(source.id, source.code, source.status, orderViews, ovenViews);
    }

    /** 执行批量接管：整批一个事务，任何一张工单不满足条件则整批回滚、无一落库 */
    @Transactional
    public TakeoverResult takeover(Long sourceOvenId, TakeoverRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new BizException("请至少选择一张工单进行接管");
        }

        // ---------- 1. 基本结构校验（不持锁） ----------
        List<TakeoverItem> items = request.items();
        for (TakeoverItem item : items) {
            if (item.orderId() == null || item.targetOvenId() == null) {
                throw new BizException("接管明细不完整：每张工单都必须指定替代烤箱");
            }
        }
        Set<Long> dupOrderIds = items.stream().map(TakeoverItem::orderId)
                .collect(Collectors.groupingBy(x -> x, Collectors.counting())).entrySet().stream()
                .filter(e -> e.getValue() > 1).map(Map.Entry::getKey).collect(Collectors.toSet());
        if (!dupOrderIds.isEmpty()) {
            throw new BizException("同一工单在本批接管中出现多次：工单 #"
                    + dupOrderIds.stream().sorted().map(String::valueOf).collect(Collectors.joining("、#")));
        }

        // ---------- 2. 按 id 升序锁定全部相关烤箱（源炉 + 去重后的目标炉），避免死锁 ----------
        Set<Long> ovenIds = new HashSet<>();
        ovenIds.add(sourceOvenId);
        for (TakeoverItem item : items) {
            ovenIds.add(item.targetOvenId());
        }
        Map<Long, Oven> ovenMap = new LinkedHashMap<>();
        for (Long id : ovenIds.stream().sorted().toList()) {
            Oven oven = ovenRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new BizException("替代烤箱不存在：" + id));
            ovenMap.put(id, oven);
        }
        Oven source = ovenMap.get(sourceOvenId);
        if (!"故障".equals(source.status)) {
            throw new BizException("只有故障烤箱才能发起接管，" + source.code + " 当前状态为：" + source.status);
        }

        // ---------- 3. 按 id 升序锁定全部被迁移工单 ----------
        Map<Long, BakeOrder> orderMap = new LinkedHashMap<>();
        for (Long id : items.stream().map(TakeoverItem::orderId).distinct().sorted().toList()) {
            BakeOrder order = orderRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new BizException("工单不存在：#" + id));
            orderMap.put(id, order);
        }

        // 配方名（用于错误提示能定位到具体工单）
        Map<Long, String> recipeNames = recipeRepository.findAll().stream()
                .collect(Collectors.toMap(r -> r.id, r -> r.name));

        // ---------- 4. 逐单校验，汇总全部原因，任一不过则整批拒绝 ----------
        List<String> errors = new ArrayList<>();
        // 本批中已经占用“烘焙中槽位”的目标炉（批内重复分配检测）
        Set<Long> bakingTargetOvenIds = new HashSet<>();

        for (TakeoverItem item : items) {
            BakeOrder order = orderMap.get(item.orderId());
            Oven target = ovenMap.get(item.targetOvenId());
            String label = "工单 #" + order.id + "（"
                    + recipeNames.getOrDefault(order.recipeId, "?") + "，" + order.trays + " 盘）";

            if (!Objects.equals(order.ovenId, source.id)) {
                errors.add(label + "：不属于故障炉 " + source.code + "，不能随本次接管迁移");
                continue;
            }
            if (!"待烤".equals(order.status) && !"烘焙中".equals(order.status)) {
                errors.add(label + "：当前状态为「" + order.status + "」，只有待烤/烘焙中工单可接管");
                continue;
            }
            if (Objects.equals(target.id, source.id)) {
                errors.add(label + "：替代烤箱不能选择故障炉本身（" + source.code + "）");
                continue;
            }
            if (order.trays > target.capacity) {
                errors.add(label + "：替代烤箱 " + target.code + " 容量不足（容量 "
                        + target.capacity + " < 工单烤盘数 " + order.trays + "）");
                continue;
            }
            if ("烘焙中".equals(order.status)) {
                // 库内已有烘焙中工单：持锁后复查，能发现并发的另一批已抢先落库
                boolean dbOccupied = orderRepository
                        .findByOvenIdAndStatus(target.id, "烘焙中").stream()
                        .anyMatch(o -> !Objects.equals(o.id, order.id));
                if (dbOccupied) {
                    errors.add(label + "：替代烤箱 " + target.code
                            + " 已有一张烘焙中工单，不能再接收第二张烘焙中工单（可能已被另一批接管抢先占用）");
                    continue;
                }
                // 本批内同一目标炉重复分配两张烘焙中工单
                if (!bakingTargetOvenIds.add(target.id)) {
                    errors.add(label + "：替代烤箱 " + target.code
                            + " 在本批接管中已分配另一张烘焙中工单，一炉同时只能有一张烘焙中工单");
                    continue;
                }
            }
            if (!"空闲".equals(target.status)) {
                errors.add(label + "：替代烤箱 " + target.code + " 当前为「" + target.status + "」，必须处于空闲");
                continue;
            }
        }

        if (!errors.isEmpty()) {
            throw new BizException("接管失败，整批未迁移（共 " + errors.size() + " 处问题，无任何工单落库）：\n"
                    + errors.stream().map(e -> "· " + e).collect(Collectors.joining("\n")));
        }

        // ---------- 5. 全部通过：批量落库 ----------
        // 收到烘焙中工单的目标炉置为「占用」；只收待烤工单的炉子保持「空闲」
        List<TakeoverResult.MovedOrderView> moved = new ArrayList<>();
        for (TakeoverItem item : items) {
            BakeOrder order = orderMap.get(item.orderId());
            Oven target = ovenMap.get(item.targetOvenId());

            order.ovenId = target.id;
            // 待烤 → 仍待烤；烘焙中 → 保留状态与原开始时间（startTime 不动）
            orderRepository.save(order);

            moved.add(new TakeoverResult.MovedOrderView(
                    order.id, order.status, order.trays,
                    target.id, target.code,
                    order.startTime == null ? null : FMT.format(order.startTime)));
        }
        for (Long targetId : bakingTargetOvenIds) {
            Oven target = ovenMap.get(targetId);
            target.status = "占用";
            ovenRepository.save(target);
        }
        // 源炉保持故障，状态不动；未在本批迁移的工单归属也原样保留
        return new TakeoverResult(source.id, source.code, moved.size(), moved);
    }
}
