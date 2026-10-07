package com.bakery.oven.service;

import com.bakery.oven.common.BizException;
import com.bakery.oven.dto.ScheduleItemView;
import com.bakery.oven.dto.ScheduleSaveRequest;
import com.bakery.oven.dto.ScheduleUnscheduledView;
import com.bakery.oven.dto.ScheduleView;
import com.bakery.oven.entity.BakeOrder;
import com.bakery.oven.entity.BakeSchedule;
import com.bakery.oven.entity.BakeScheduleItem;
import com.bakery.oven.entity.BakeScheduleMeta;
import com.bakery.oven.entity.BreadRecipe;
import com.bakery.oven.entity.Oven;
import com.bakery.oven.repository.BakeOrderRepository;
import com.bakery.oven.repository.BakeScheduleItemRepository;
import com.bakery.oven.repository.BakeScheduleMetaRepository;
import com.bakery.oven.repository.BakeScheduleRepository;
import com.bakery.oven.repository.BreadRecipeRepository;
import com.bakery.oven.repository.OvenRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 生产排产：把「待烤」工单按优先级顺序排成可解释的建议。
 * 规则：
 *  - 只有「待烤」工单参与排产；已开始/已完成的工单不进排产列表，也不能被拖动/保存；
 *  - 故障烤箱不参与排产；占用烤箱按「当前烘焙中工单预计出炉时间」释放后参与；
 *  - 按优先级逐单分配到「预计完成最早」的容量足够烤箱，一炉同时只烤一张（与线下一炉一张烘焙中一致）；
 *  - 没有可用烤箱、容量不足、预计逾期的工单进入未排入区并给出原因，不伪装成已安排；
 *  - 排产只是建议：结果落在 bake_schedule* 表，绝不回写工单状态与故障接管归属；
 *  - 保存版本时锁定 bake_schedule_meta 单行：baseVersionId 不一致说明他人已保存更新版本，
 *    fingerprint 不一致说明排产基于旧数据（较慢的旧计算不能覆盖新版本），两者都拒绝。
 */
@Service
public class ScheduleService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    /** 截止时间缓冲小于该分钟数视为「紧张」 */
    private static final long TIGHT_BUFFER_MINUTES = 30;

    private final OvenRepository ovenRepository;
    private final BakeOrderRepository orderRepository;
    private final BreadRecipeRepository recipeRepository;
    private final BakeScheduleRepository scheduleRepository;
    private final BakeScheduleItemRepository itemRepository;
    private final BakeScheduleMetaRepository metaRepository;

    public ScheduleService(OvenRepository ovenRepository,
                           BakeOrderRepository orderRepository,
                           BreadRecipeRepository recipeRepository,
                           BakeScheduleRepository scheduleRepository,
                           BakeScheduleItemRepository itemRepository,
                           BakeScheduleMetaRepository metaRepository) {
        this.ovenRepository = ovenRepository;
        this.orderRepository = orderRepository;
        this.recipeRepository = recipeRepository;
        this.scheduleRepository = scheduleRepository;
        this.itemRepository = itemRepository;
        this.metaRepository = metaRepository;
    }

    /** 当前排产视图：最新版本信息 + 基于最新数据按版本优先级重算的建议顺序 */
    public ScheduleView current() {
        BakeSchedule latest = scheduleRepository.findFirstByOrderByIdDesc().orElse(null);
        List<Long> priorities = null;
        if (latest != null) {
            priorities = itemRepository.findByScheduleIdOrderBySeq(latest.id).stream()
                    .map(item -> item.orderId).toList();
        }
        Computed computed = compute(priorities);
        boolean stale = latest != null && !latest.fingerprint.equals(computed.fingerprint());
        return new ScheduleView(
                latest == null ? null : latest.id,
                latest == null ? null : latest.name,
                latest == null ? null : latest.createdBy,
                latest == null ? null : latest.createdAt,
                stale,
                computed.fingerprint(),
                computed.priorities(),
                computed.scheduled(),
                computed.unscheduled());
    }

    /** 试算：按操作员调整后的优先级顺序重算，不落库（拖动排序时调用） */
    public ScheduleView preview(List<Long> priorities) {
        BakeSchedule latest = scheduleRepository.findFirstByOrderByIdDesc().orElse(null);
        Computed computed = compute(priorities);
        boolean stale = latest != null && !latest.fingerprint.equals(computed.fingerprint());
        return new ScheduleView(
                latest == null ? null : latest.id,
                latest == null ? null : latest.name,
                latest == null ? null : latest.createdBy,
                latest == null ? null : latest.createdAt,
                stale,
                computed.fingerprint(),
                computed.priorities(),
                computed.scheduled(),
                computed.unscheduled());
    }

    /**
     * 保存排产版本。并发裁决：
     * 先锁 bake_schedule_meta 单行串行化并发保存，再校验版本基线与数据指纹，
     * 两名操作员同时保存只有一方能落库；失败方拿到明确提示，刷新后看到最新顺序。
     */
    @Transactional
    public ScheduleView save(ScheduleSaveRequest request) {
        if (request == null || request.priorities() == null || request.priorities().isEmpty()) {
            throw new BizException("排产顺序为空：没有可保存的待烤工单");
        }
        // ---------- 1. 结构校验（不持锁） ----------
        List<Long> priorities = request.priorities();
        Set<Long> dup = priorities.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream().filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        if (!dup.isEmpty()) {
            throw new BizException("排产顺序中工单重复：#"
                    + dup.stream().sorted().map(String::valueOf).collect(Collectors.joining("、#")));
        }

        // ---------- 2. 锁元数据单行，串行化并发保存 ----------
        BakeScheduleMeta meta = metaRepository.findByIdForUpdate(1L)
                .orElseThrow(() -> new BizException("排产元数据缺失，请联系管理员初始化 bake_schedule_meta"));
        if (!Objects.equals(meta.latestVersionId, request.baseVersionId())) {
            throw new BizException("保存失败：他人已保存更新的排产版本（当前最新为 "
                    + (meta.latestVersionId == null ? "无" : "#" + meta.latestVersionId)
                    + "），本次未生效，请刷新查看最新顺序与冲突提示");
        }

        // ---------- 3. 基于当前数据重算并校验指纹 ----------
        Computed computed = compute(priorities);
        if (!computed.fingerprint().equals(request.fingerprint())) {
            throw new BizException("保存失败：烤箱状态、工单烤盘数或交付截止时间已变化，"
                    + "当前排产基于旧数据，已标为过期，请重算后再保存");
        }

        // ---------- 4. 优先级必须恰好覆盖当前全部待烤工单 ----------
        Set<Long> pendingIds = orderRepository.findByStatus("待烤").stream()
                .map(o -> o.id).collect(Collectors.toSet());
        List<Long> notPending = priorities.stream().filter(id -> !pendingIds.contains(id)).toList();
        if (!notPending.isEmpty()) {
            throw new BizException("工单 #"
                    + notPending.stream().sorted().map(String::valueOf).collect(Collectors.joining("、#"))
                    + " 已不是「待烤」（可能已开始或完成），不能参与排产，请刷新后重试");
        }
        Set<Long> missing = new HashSet<>(pendingIds);
        missing.removeAll(priorities);
        if (!missing.isEmpty()) {
            throw new BizException("还有 " + missing.size() + " 张待烤工单未纳入排产顺序（#"
                    + missing.stream().sorted().map(String::valueOf).collect(Collectors.joining("、#"))
                    + "），请刷新后重试");
        }

        // ---------- 5. 落库版本头 + 明细快照（只写排产表，不动工单/烤箱） ----------
        BakeSchedule version = new BakeSchedule();
        version.name = (request.name() == null || request.name().isBlank())
                ? "排产 " + DateTimeFormatter.ofPattern("MM-dd HH:mm").format(LocalDateTime.now())
                : request.name().trim();
        version.fingerprint = computed.fingerprint();
        version.baseVersionId = request.baseVersionId();
        version.scheduledCount = computed.scheduled().size();
        version.unscheduledCount = computed.unscheduled().size();
        version.createdBy = (request.operator() == null || request.operator().isBlank())
                ? "操作员" : request.operator().trim();
        version.createdAt = LocalDateTime.now();
        scheduleRepository.save(version);

        Map<Long, ScheduleItemView> scheduledById = computed.scheduled().stream()
                .collect(Collectors.toMap(ScheduleItemView::orderId, Function.identity()));
        Map<Long, ScheduleUnscheduledView> unscheduledById = computed.unscheduled().stream()
                .collect(Collectors.toMap(ScheduleUnscheduledView::orderId, Function.identity()));
        for (int seq = 0; seq < computed.priorities().size(); seq++) {
            Long orderId = computed.priorities().get(seq);
            BakeScheduleItem item = new BakeScheduleItem();
            item.scheduleId = version.id;
            item.orderId = orderId;
            item.seq = seq;
            ScheduleItemView s = scheduledById.get(orderId);
            if (s != null) {
                item.scheduled = true;
                item.ovenId = s.ovenId();
                item.ovenCode = s.ovenCode();
                item.estStart = s.estStart();
                item.estFinish = s.estFinish();
                item.risk = s.risk();
                item.reason = s.note();
            } else {
                ScheduleUnscheduledView u = unscheduledById.get(orderId);
                item.scheduled = false;
                item.reason = u == null ? "未排入" : u.reason();
            }
            itemRepository.save(item);
        }

        meta.latestVersionId = version.id;
        metaRepository.save(meta);
        return current();
    }

    // =====================================================================
    // 排产计算（纯函数：当前库数据 + 优先级顺序 → 建议顺序 + 未排入区）
    // =====================================================================

    private Computed compute(List<Long> priorities) {
        List<Oven> ovens = ovenRepository.findAll();
        List<BakeOrder> allOrders = orderRepository.findAll();
        List<BreadRecipe> recipes = recipeRepository.findAll();
        String fingerprint = fingerprint(ovens, allOrders, recipes);

        Map<Long, BreadRecipe> recipeMap = recipes.stream()
                .collect(Collectors.toMap(r -> r.id, Function.identity()));
        Map<Long, Oven> ovenMap = ovens.stream()
                .collect(Collectors.toMap(o -> o.id, Function.identity()));
        LocalDateTime now = LocalDateTime.now();

        // 可用烤箱（非故障）及各自预计释放时间：空闲=现在；占用=当前烘焙中工单预计出炉时间
        List<Oven> usable = ovens.stream()
                .filter(o -> !"故障".equals(o.status))
                .sorted(Comparator.comparing(o -> o.id))
                .toList();
        Map<Long, LocalDateTime> freeAt = new HashMap<>();
        Map<Long, LocalDateTime> busyRelease = new HashMap<>();
        for (Oven oven : usable) {
            LocalDateTime t = now;
            if ("占用".equals(oven.status)) {
                t = allOrders.stream()
                        .filter(o -> "烘焙中".equals(o.status) && Objects.equals(o.ovenId, oven.id))
                        .map(o -> {
                            BreadRecipe r = recipeMap.get(o.recipeId);
                            int minutes = r == null ? 0 : r.bakeMinutes;
                            return o.startTime == null ? now : o.startTime.plusMinutes(minutes);
                        })
                        .max(Comparator.naturalOrder())
                        .orElse(now);
                if (t.isBefore(now)) {
                    t = now;
                }
                busyRelease.put(oven.id, t);
            }
            freeAt.put(oven.id, t);
        }
        int maxCapacity = usable.stream().mapToInt(o -> o.capacity).max().orElse(0);

        // 有效优先级：给定顺序 ∩ 当前待烤，未覆盖的待烤按截止时间升序（无截止时间排最后）补尾
        List<BakeOrder> pending = allOrders.stream()
                .filter(o -> "待烤".equals(o.status))
                .toList();
        Map<Long, BakeOrder> pendingMap = pending.stream()
                .collect(Collectors.toMap(o -> o.id, Function.identity()));
        List<BakeOrder> ordered = new ArrayList<>();
        if (priorities != null) {
            for (Long id : priorities) {
                BakeOrder o = pendingMap.remove(id);
                if (o != null) {
                    ordered.add(o);
                }
            }
        }
        pendingMap.values().stream()
                .sorted(Comparator
                        .comparing((BakeOrder o) -> o.deadline == null ? LocalDateTime.MAX : o.deadline)
                        .thenComparing(o -> o.id))
                .forEach(ordered::add);

        List<ScheduleItemView> scheduled = new ArrayList<>();
        List<ScheduleUnscheduledView> unscheduled = new ArrayList<>();
        Map<Long, Integer> queuedPerOven = new HashMap<>();

        for (BakeOrder order : ordered) {
            BreadRecipe recipe = recipeMap.get(order.recipeId);
            Oven ownOven = order.ovenId == null ? null : ovenMap.get(order.ovenId);
            String ownCode = ownOven == null ? "?" : ownOven.code;
            String label = "工单 #" + order.id + "（"
                    + (recipe == null ? "?" : recipe.name) + "，" + order.trays + " 盘）";

            if (recipe == null) {
                unscheduled.add(new ScheduleUnscheduledView(order.id, "?", order.trays, order.deadline,
                        order.ovenId, ownCode, "recipe",
                        label + "：配方不存在，无法估算烘焙耗时"));
                continue;
            }
            if (usable.isEmpty()) {
                unscheduled.add(new ScheduleUnscheduledView(order.id, recipe.name, order.trays, order.deadline,
                        order.ovenId, ownCode, "no-oven",
                        label + "：容量预警，没有可用烤箱（全部故障），请修复或接管后再排产"));
                continue;
            }
            List<Oven> fit = usable.stream().filter(o -> order.trays <= o.capacity).toList();
            if (fit.isEmpty()) {
                unscheduled.add(new ScheduleUnscheduledView(order.id, recipe.name, order.trays, order.deadline,
                        order.ovenId, ownCode, "capacity",
                        label + "：容量预警，需 " + order.trays + " 盘，可用烤箱最大容量仅 " + maxCapacity
                                + " 盘，无法排入"));
                continue;
            }
            // 预计完成最早的烤箱优先；并列时取炉号 id 小者，保证结果可解释、可复现
            Oven best = null;
            LocalDateTime bestStart = null;
            LocalDateTime bestFinish = null;
            for (Oven oven : fit) {
                LocalDateTime start = freeAt.get(oven.id);
                LocalDateTime finish = start.plusMinutes(recipe.bakeMinutes);
                if (best == null || finish.isBefore(bestFinish)
                        || (finish.equals(bestFinish) && oven.id < best.id)) {
                    best = oven;
                    bestStart = start;
                    bestFinish = finish;
                }
            }
            if (order.deadline != null && bestFinish.isAfter(order.deadline)) {
                unscheduled.add(new ScheduleUnscheduledView(order.id, recipe.name, order.trays, order.deadline,
                        order.ovenId, ownCode, "overdue",
                        label + "：预计逾期，最早也要 " + FMT.format(bestFinish) + " 才能完成（烤箱 " + best.code
                                + "），超过交付截止时间 " + FMT.format(order.deadline)
                                + "，请调整优先级提前或协商交期"));
                continue;
            }

            String risk;
            if (order.deadline == null) {
                risk = "无截止时间";
            } else {
                long buffer = ChronoUnit.MINUTES.between(bestFinish, order.deadline);
                risk = buffer < TIGHT_BUFFER_MINUTES ? "紧张" : "正常";
            }
            List<String> notes = new ArrayList<>();
            if (busyRelease.containsKey(best.id)) {
                notes.add(best.code + " 占用中，预计 " + FMT.format(busyRelease.get(best.id)) + " 释放");
            }
            int queued = queuedPerOven.getOrDefault(best.id, 0);
            if (queued > 0) {
                notes.add("排在该炉第 " + (queued + 1) + " 位，前序 " + queued + " 张工单完成后上炉");
            }
            if (notes.isEmpty()) {
                notes.add("烤箱空闲，可立即上炉");
            }
            scheduled.add(new ScheduleItemView(order.id, recipe.name, order.trays, order.deadline,
                    best.id, best.code, best.capacity,
                    order.ovenId, ownCode, ownOven == null ? null : ownOven.status,
                    bestStart, bestFinish, risk, String.join("；", notes)));
            freeAt.put(best.id, bestFinish);
            queuedPerOven.put(best.id, queued + 1);
        }

        return new Computed(fingerprint,
                ordered.stream().map(o -> o.id).toList(),
                scheduled, unscheduled);
    }

    /**
     * 排产输入数据指纹：烤箱状态/容量、工单归属/烤盘数/状态/开始与截止时间、配方耗时。
     * 任一变化都会改变指纹，已保存版本随之标为「过期」，需基于最新数据重算。
     */
    private String fingerprint(List<Oven> ovens, List<BakeOrder> orders, List<BreadRecipe> recipes) {
        StringBuilder sb = new StringBuilder();
        ovens.stream().sorted(Comparator.comparing(o -> o.id)).forEach(o -> sb.append('O')
                .append(o.id).append(',').append(o.status).append(',').append(o.capacity).append(';'));
        recipes.stream().sorted(Comparator.comparing(r -> r.id)).forEach(r -> sb.append('R')
                .append(r.id).append(',').append(r.bakeMinutes).append(',').append(r.needTrays).append(';'));
        orders.stream().sorted(Comparator.comparing(o -> o.id)).forEach(o -> sb.append('B')
                .append(o.id).append(',').append(o.ovenId).append(',').append(o.recipeId).append(',')
                .append(o.trays).append(',').append(o.status).append(',')
                .append(o.startTime).append(',').append(o.deadline).append(';'));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 一次排产计算的结果 */
    private record Computed(
            String fingerprint,
            List<Long> priorities,
            List<ScheduleItemView> scheduled,
            List<ScheduleUnscheduledView> unscheduled
    ) {
    }
}
