package com.bakery.oven.service;

import com.bakery.oven.common.BizException;
import com.bakery.oven.common.ScheduleConflictException;
import com.bakery.oven.dto.LockedOrderView;
import com.bakery.oven.dto.ScheduleOvenView;
import com.bakery.oven.dto.SchedulePreviewRequest;
import com.bakery.oven.dto.ScheduleSaveRequest;
import com.bakery.oven.dto.ScheduleVersionSummary;
import com.bakery.oven.dto.ScheduleView;
import com.bakery.oven.dto.ScheduledItemView;
import com.bakery.oven.dto.UnscheduledItemView;
import com.bakery.oven.entity.BakeOrder;
import com.bakery.oven.entity.BreadRecipe;
import com.bakery.oven.entity.Oven;
import com.bakery.oven.entity.ScheduleItem;
import com.bakery.oven.entity.ScheduleState;
import com.bakery.oven.entity.ScheduleVersion;
import com.bakery.oven.repository.BakeOrderRepository;
import com.bakery.oven.repository.BreadRecipeRepository;
import com.bakery.oven.repository.OvenRepository;
import com.bakery.oven.repository.ScheduleItemRepository;
import com.bakery.oven.repository.ScheduleStateRepository;
import com.bakery.oven.repository.ScheduleVersionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 生产排产：把「待烤」工单按交付截止、配方耗时与可用烤箱容量排成可解释的建议顺序。
 *
 * 口径说明：
 *  - 排产只是建议：只写 schedule_version / schedule_item，绝不改写 bake_order / oven
 *    （工单状态、挂炉归属、故障接管结果都不受影响）；
 *  - 已开始（烘焙中及以后）的工单不参与排产，也不能通过 priorities 塞入；
 *  - 每个版本保存当时的输入指纹与快照：烤箱状态/容量、待烤与烘焙中工单、配方耗时
 *    任一变化，旧版本即标记「已过期」，可基于最新数据重算；
 *  - 保存走 schedule_state 行锁串行 + baseVersionNo 乐观校验：两人同时保存只保留一个版本，
 *    较慢的旧计算（保存时版本号已落后）会被拒绝，不能覆盖新版本；
 *  - 预计开始/完成、烤箱分配、容量与逾期判定全部在本类计算，前端只提交优先级序列，
 *    避免“只在前端排序/只存一串编号”漏掉跨烤箱容量与逾期风险。
 */
@Service
public class ScheduleService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 预计完成时间距交付截止不足该分钟数 → 截止风险「紧张」 */
    private static final long TIGHT_SLACK_MINUTES = 15;

    private final OvenRepository ovenRepository;
    private final BakeOrderRepository orderRepository;
    private final BreadRecipeRepository recipeRepository;
    private final ScheduleVersionRepository versionRepository;
    private final ScheduleItemRepository itemRepository;
    private final ScheduleStateRepository stateRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScheduleService(OvenRepository ovenRepository,
                           BakeOrderRepository orderRepository,
                           BreadRecipeRepository recipeRepository,
                           ScheduleVersionRepository versionRepository,
                           ScheduleItemRepository itemRepository,
                           ScheduleStateRepository stateRepository) {
        this.ovenRepository = ovenRepository;
        this.orderRepository = orderRepository;
        this.recipeRepository = recipeRepository;
        this.versionRepository = versionRepository;
        this.itemRepository = itemRepository;
        this.stateRepository = stateRepository;
    }

    // ================= 查询 =================

    /** 当前排产：有已保存版本返回最新版本（含过期判定），否则返回基于最新数据的未保存预览 */
    @Transactional(readOnly = true)
    public ScheduleView current() {
        Integer latestNo = latestVersionNo();
        Optional<ScheduleVersion> latest = versionRepository.findTopByOrderByVersionNoDesc();
        if (latest.isEmpty()) {
            ComputedSchedule cs = compute(null, LocalDateTime.now());
            return toViewFromComputed(cs, false, false, null, latestNo == 0 ? null : latestNo,
                    false, List.of());
        }
        ScheduleVersion v = latest.get();
        return toViewFromVersion(v, itemRepository.findByVersionId(v.id), latestNo);
    }

    /** 查看指定版本（历史版本只读展示，stale 仍按当前数据判定） */
    @Transactional(readOnly = true)
    public ScheduleView version(Long id) {
        ScheduleVersion v = versionRepository.findById(id)
                .orElseThrow(() -> new BizException("排产版本不存在：" + id));
        return toViewFromVersion(v, itemRepository.findByVersionId(v.id), latestVersionNo());
    }

    /** 版本历史（新→旧） */
    @Transactional(readOnly = true)
    public List<ScheduleVersionSummary> versions() {
        Integer latestNo = latestVersionNo();
        return versionRepository.findAllByOrderByVersionNoDesc().stream()
                .map(v -> new ScheduleVersionSummary(v.id, v.versionNo, v.baseVersionNo, v.operatorName,
                        v.scheduledCount, v.unscheduledCount, fmt(v.createdAt),
                        Objects.equals(v.versionNo, latestNo)))
                .toList();
    }

    /** 实时预览：按给定优先级基于最新数据计算，不落库（重算 / 拖动后即时刷新用） */
    @Transactional(readOnly = true)
    public ScheduleView preview(SchedulePreviewRequest req) {
        List<Long> priorities = req == null ? null : req.priorities();
        ComputedSchedule cs = compute(priorities, LocalDateTime.now());
        Integer latestNo = latestVersionNo();
        return toViewFromComputed(cs, false, false, null, latestNo == 0 ? null : latestNo,
                false, List.of());
    }

    // ================= 保存 =================

    /**
     * 保存排产版本：行锁串行 + 基于版本号乐观校验。
     * 两名操作员同时保存时，后到者在行锁等待后看到最新版本号已变 → 冲突拒绝，
     * 全库只保留一个最新版本；失败方刷新后看到最新顺序与冲突提示。
     */
    @Transactional
    public ScheduleView save(ScheduleSaveRequest req) {
        List<Long> priorities = req == null ? null : req.priorities();
        Integer base = req == null || req.baseVersionNo() == null ? null : req.baseVersionNo().intValue();

        // 1. 锁定状态行，串行化并发保存
        ScheduleState state = stateRepository.findByIdForUpdate(1)
                .orElseThrow(() -> new BizException("排产状态未初始化（schedule_state 缺行）"));
        int latest = state.latestVersionNo;
        // 2. 乐观校验：基于旧版本（或较慢的旧计算）的保存不能覆盖新版本
        if (base == null ? latest != 0 : base.intValue() != latest) {
            throw new ScheduleConflictException(latest, base);
        }

        // 3. 基于当前最新数据计算（指纹与落库内容一致）
        ComputedSchedule cs = compute(priorities, LocalDateTime.now());

        // 4. 落库版本与明细（只写排产表，绝不动工单/烤箱）
        ScheduleVersion version = new ScheduleVersion();
        version.versionNo = latest + 1;
        version.baseVersionNo = base;
        String operator = req != null && req.operatorName() != null && !req.operatorName().isBlank()
                ? req.operatorName().trim() : "操作员";
        version.operatorName = operator.length() > 32 ? operator.substring(0, 32) : operator;
        version.inputFingerprint = cs.fingerprint;
        version.inputSnapshot = cs.snapshotJson;
        version.scheduledCount = cs.scheduled.size();
        version.unscheduledCount = cs.unscheduled.size();
        version.createdAt = LocalDateTime.now();
        versionRepository.save(version);

        LocalDateTime now = LocalDateTime.now();
        for (PlacedItem p : cs.scheduled) {
            ScheduleItem item = new ScheduleItem();
            item.versionId = version.id;
            item.orderId = p.order.id;
            item.seqNo = p.seq;
            item.placed = true;
            item.ovenId = p.slot.oven.id;
            item.ovenCode = p.slot.oven.code;
            item.estStartTime = p.estStart;
            item.estFinishTime = p.estFinish;
            item.dueTime = p.order.dueTime;
            item.deadlineRisk = p.deadlineRisk;
            item.reasonType = null;
            item.capacityNote = p.capacityNote;
            item.reason = p.reason;
            item.createdAt = now;
            itemRepository.save(item);
        }
        for (UnplacedItem u : cs.unscheduled) {
            ScheduleItem item = new ScheduleItem();
            item.versionId = version.id;
            item.orderId = u.order.id;
            item.seqNo = null;
            item.placed = false;
            item.dueTime = u.order.dueTime;
            item.reasonType = u.reasonType;
            item.reason = u.reason;
            item.createdAt = now;
            itemRepository.save(item);
        }

        state.latestVersionNo = version.versionNo;
        stateRepository.save(state);

        return toViewFromComputed(cs, true, false, version, version.versionNo, false, List.of());
    }

    // ================= 排产计算（纯计算，不持锁、不落库） =================

    /**
     * 计算建议顺序：
     *  1. 烤箱可用性：故障不可用；占用=炉上烘焙中工单预计完成时刻；空闲=立即可用；
     *  2. 优先级：默认 交付截止(早优先) → 配方耗时(短优先) → 创建时间；操作员可给定 priorities 覆盖；
     *  3. 逐单模拟：选“最早可排、且容量够的最小炉”，预计完成晚于交付截止的工单留在未排入区且不占炉位；
     *     没有可用烤箱 / 容量不足的工单同样留在未排入区并给出原因。
     */
    public ComputedSchedule compute(List<Long> priorities, LocalDateTime now) {
        List<Oven> ovens = ovenRepository.findAll().stream()
                .sorted(Comparator.comparing(o -> o.id)).toList();
        List<BakeOrder> allOrders = orderRepository.findAll();
        List<BreadRecipe> recipes = recipeRepository.findAll();
        Map<Long, BreadRecipe> recipeMap = recipes.stream().collect(Collectors.toMap(r -> r.id, r -> r));
        Map<Long, Oven> ovenMap = ovens.stream().collect(Collectors.toMap(o -> o.id, o -> o));

        Snapshot snap = snapshot(ovens, allOrders, recipes);

        Map<Long, LocalDateTime> busyUntil = busyUntil(allOrders, recipeMap);
        List<OvenSlot> slots = new ArrayList<>();
        for (Oven oven : ovens) {
            slots.add(slotState(oven, busyUntil, now));
        }

        List<BakeOrder> pending = allOrders.stream()
                .filter(o -> "待烤".equals(o.status))
                .sorted(Comparator.comparing(o -> o.id))
                .toList();
        List<BakeOrder> sequence = sequence(pending, priorities, recipeMap);
        boolean manual = priorities != null && !priorities.isEmpty();

        ComputedSchedule result = new ComputedSchedule();
        result.snapshotJson = snap.json();
        result.fingerprint = snap.fingerprint();
        result.pendingCount = pending.size();
        result.slots = slots;
        result.ovens = ovens;
        result.allOrders = allOrders;
        result.recipes = recipes;

        boolean anyUsable = slots.stream().anyMatch(s -> s.usable);
        int maxUsableCapacity = slots.stream().filter(s -> s.usable)
                .mapToInt(s -> s.oven.capacity).max().orElse(0);

        int seq = 0;
        for (BakeOrder order : sequence) {
            BreadRecipe recipe = recipeMap.get(order.recipeId);
            if (recipe == null) {
                result.unscheduled.add(UnplacedItem.of(order, null, "RECIPE_MISSING",
                        "配方不存在或已删除，无法估算烘焙耗时"));
                continue;
            }
            List<OvenSlot> capable = slots.stream()
                    .filter(s -> s.usable && s.oven.capacity >= order.trays)
                    .toList();
            if (capable.isEmpty()) {
                if (!anyUsable) {
                    result.unscheduled.add(UnplacedItem.of(order, recipe, "NO_OVEN",
                            "无可用烤箱：全部烤箱故障或释放时间不可预计，请先修复/释放烤箱后再重算"));
                } else {
                    result.unscheduled.add(UnplacedItem.of(order, recipe, "CAPACITY",
                            "容量预警：工单 " + order.trays + " 盘超出可用烤箱最大容量 "
                                    + maxUsableCapacity + " 盘，无法排入"));
                }
                continue;
            }
            OvenSlot best = capable.stream().min(Comparator
                            .comparing((OvenSlot s) -> s.freeAt)
                            .thenComparing(s -> s.oven.capacity)
                            .thenComparing(s -> s.oven.id))
                    .orElseThrow();
            LocalDateTime estStart = best.freeAt;
            LocalDateTime estFinish = estStart.plusMinutes(recipe.bakeMinutes);
            if (order.dueTime != null && estFinish.isAfter(order.dueTime)) {
                result.unscheduled.add(UnplacedItem.of(order, recipe, "OVERDUE",
                        "预计逾期：最早可排 " + best.oven.code + " " + FMT.format(estStart) + " 开烤、"
                                + FMT.format(estFinish) + " 完成，晚于交付截止 " + FMT.format(order.dueTime)
                                + "；工单留在未排入区，不占用炉位"));
                continue;
            }
            seq++;
            best.freeAt = estFinish;
            result.scheduled.add(buildPlaced(seq, order, recipe, best, estStart, estFinish, manual, ovenMap, now));
        }
        return result;
    }

    // ================= 计算内部结构 =================

    /** 烤箱槽位：排产模拟中的可用性与释放时间 */
    public static class OvenSlot {
        public Oven oven;
        public boolean usable;
        public LocalDateTime freeAt;
        public String note;

        OvenSlot(Oven oven) {
            this.oven = oven;
        }
    }

    /** 已排入项（计算结果） */
    public static class PlacedItem {
        public int seq;
        public BakeOrder order;
        public BreadRecipe recipe;
        public OvenSlot slot;
        public LocalDateTime estStart;
        public LocalDateTime estFinish;
        public String deadlineRisk;
        public String capacityNote;
        public String reason;
    }

    /** 未排入项（计算结果） */
    public static class UnplacedItem {
        public BakeOrder order;
        public BreadRecipe recipe;
        public String reasonType;
        public String reason;

        static UnplacedItem of(BakeOrder order, BreadRecipe recipe, String reasonType, String reason) {
            UnplacedItem u = new UnplacedItem();
            u.order = order;
            u.recipe = recipe;
            u.reasonType = reasonType;
            u.reason = reason;
            return u;
        }
    }

    /** 一次排产计算的完整结果（含输入快照与指纹） */
    public static class ComputedSchedule {
        public List<PlacedItem> scheduled = new ArrayList<>();
        public List<UnplacedItem> unscheduled = new ArrayList<>();
        public List<OvenSlot> slots = new ArrayList<>();
        public List<Oven> ovens = new ArrayList<>();
        public List<BakeOrder> allOrders = new ArrayList<>();
        public List<BreadRecipe> recipes = new ArrayList<>();
        public String snapshotJson;
        public String fingerprint;
        public int pendingCount;
    }

    private record Snapshot(String json, String fingerprint) {
    }

    // ================= 计算辅助 =================

    /** 每台占用烤箱的预计释放时刻 = 炉上烘焙中工单的 开始时间 + 配方耗时（取最晚） */
    private Map<Long, LocalDateTime> busyUntil(List<BakeOrder> allOrders, Map<Long, BreadRecipe> recipeMap) {
        Map<Long, LocalDateTime> busyUntil = new HashMap<>();
        for (BakeOrder o : allOrders) {
            if (!"烘焙中".equals(o.status) || o.ovenId == null || o.startTime == null) {
                continue;
            }
            BreadRecipe r = recipeMap.get(o.recipeId);
            if (r == null) {
                continue;
            }
            LocalDateTime freeAt = o.startTime.plusMinutes(r.bakeMinutes);
            busyUntil.merge(o.ovenId, freeAt, (a, b) -> a.isAfter(b) ? a : b);
        }
        return busyUntil;
    }

    /** 单台烤箱的排产可用性：故障不可用；占用按预计释放时刻；空闲立即可用 */
    private OvenSlot slotState(Oven oven, Map<Long, LocalDateTime> busyUntil, LocalDateTime now) {
        OvenSlot slot = new OvenSlot(oven);
        if ("故障".equals(oven.status)) {
            slot.usable = false;
            slot.note = "故障停用，修复后才能排产";
        } else if ("占用".equals(oven.status)) {
            LocalDateTime freeAt = busyUntil.get(oven.id);
            if (freeAt == null) {
                slot.usable = false;
                slot.note = "占用中（炉上无烘焙中工单，释放时间不可预计）";
            } else {
                slot.usable = true;
                slot.freeAt = freeAt.isAfter(now) ? freeAt : now;
                slot.note = "占用中，预计 " + FMT.format(freeAt) + " 释放";
            }
        } else {
            slot.usable = true;
            slot.freeAt = now;
            slot.note = "空闲，可立即排产";
        }
        return slot;
    }

    /** 优先级序列：操作员给定的顺序优先，未覆盖的待烤工单按默认规则补在队尾 */
    private List<BakeOrder> sequence(List<BakeOrder> pending, List<Long> priorities,
                                     Map<Long, BreadRecipe> recipeMap) {
        List<BakeOrder> defaultSorted = pending.stream().sorted(defaultOrder(recipeMap)).toList();
        if (priorities == null || priorities.isEmpty()) {
            return defaultSorted;
        }
        Map<Long, BakeOrder> byId = pending.stream().collect(Collectors.toMap(o -> o.id, o -> o));
        List<BakeOrder> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long id : priorities) {
            if (id == null) {
                continue;
            }
            if (!seen.add(id)) {
                throw new BizException("排产顺序中工单 #" + id + " 重复出现");
            }
            BakeOrder order = byId.get(id);
            if (order == null) {
                BakeOrder any = orderRepository.findById(id).orElse(null);
                if (any != null) {
                    throw new BizException("工单 #" + id + " 当前状态为「" + any.status
                            + "」，已开始或已完成的工单不能参与排产调整");
                }
                throw new BizException("工单 #" + id + " 不存在，请刷新后重试");
            }
            result.add(order);
        }
        for (BakeOrder o : defaultSorted) {
            if (!seen.contains(o.id)) {
                result.add(o);
            }
        }
        return result;
    }

    /** 默认排序：交付截止早优先（无截止靠后）→ 配方耗时短优先 → 创建早优先 → id */
    private Comparator<BakeOrder> defaultOrder(Map<Long, BreadRecipe> recipeMap) {
        return Comparator
                .comparing((BakeOrder o) -> o.dueTime == null ? 1 : 0)
                .thenComparing(o -> o.dueTime == null ? LocalDateTime.MAX : o.dueTime)
                .thenComparing(o -> {
                    BreadRecipe r = recipeMap.get(o.recipeId);
                    return r == null ? Integer.MAX_VALUE : r.bakeMinutes;
                })
                .thenComparing(o -> o.createdAt == null ? LocalDateTime.MAX : o.createdAt)
                .thenComparing(o -> o.id);
    }

    /** 构造已排入项：截止风险、容量说明与可解释原因 */
    private PlacedItem buildPlaced(int seq, BakeOrder order, BreadRecipe recipe, OvenSlot slot,
                                   LocalDateTime estStart, LocalDateTime estFinish, boolean manual,
                                   Map<Long, Oven> ovenMap, LocalDateTime now) {
        PlacedItem p = new PlacedItem();
        p.seq = seq;
        p.order = order;
        p.recipe = recipe;
        p.slot = slot;
        p.estStart = estStart;
        p.estFinish = estFinish;

        if (order.dueTime == null) {
            p.deadlineRisk = "无";
        } else {
            long slack = ChronoUnit.MINUTES.between(estFinish, order.dueTime);
            p.deadlineRisk = slack <= TIGHT_SLACK_MINUTES ? "紧张" : "无";
        }

        Oven origin = order.ovenId == null ? null : ovenMap.get(order.ovenId);
        StringBuilder cap = new StringBuilder();
        cap.append(order.trays >= slot.oven.capacity
                ? "占满整炉（" + order.trays + "/" + slot.oven.capacity + " 盘）"
                : "占用 " + order.trays + "/" + slot.oven.capacity + " 盘");
        if (origin != null && origin.capacity < order.trays) {
            cap.append("；原挂 " + origin.code + " 容量 " + origin.capacity + " 盘已装不下本单");
        }
        p.capacityNote = cap.toString();

        String posPart = "第 " + seq + " 顺位·"
                + (manual ? "按操作员调整的优先级"
                          : (order.dueTime != null
                             ? "交付截止 " + FMT.format(order.dueTime) + "，早截止优先"
                             : "无交付截止，默认靠后"));
        boolean immediate = !estStart.isAfter(now);
        String ovenPart;
        if (origin != null && Objects.equals(origin.id, slot.oven.id)) {
            ovenPart = "沿用原挂炉 " + slot.oven.code
                    + (immediate ? "，现可开烤" : "，待其 " + FMT.format(estStart) + " 释放");
        } else {
            String originNote = origin == null ? "原未挂炉"
                    : "原挂 " + origin.code + ("故障".equals(origin.status) ? "（故障停用）"
                            : origin.capacity < order.trays ? "（容量不足）" : "（" + origin.status + "）");
            ovenPart = "建议改挂 " + slot.oven.code + "（"
                    + (immediate ? "现可开烤" : FMT.format(estStart) + " 释放") + "），" + originNote;
        }
        p.reason = posPart + "；" + ovenPart;
        return p;
    }

    // ================= 视图组装 =================

    private ScheduleView toViewFromComputed(ComputedSchedule cs, boolean saved, boolean historical,
                                            ScheduleVersion v, Integer latestVersionNo,
                                            boolean stale, List<String> staleReasons) {
        Map<Long, Oven> ovenMap = cs.ovens.stream().collect(Collectors.toMap(o -> o.id, o -> o));
        Map<Long, BreadRecipe> recipeMap = cs.recipes.stream().collect(Collectors.toMap(r -> r.id, r -> r));

        List<ScheduledItemView> scheduled = cs.scheduled.stream().map(p -> {
            Oven origin = p.order.ovenId == null ? null : ovenMap.get(p.order.ovenId);
            return new ScheduledItemView(
                    p.seq, p.order.id, p.order.recipeId, p.recipe.name,
                    p.order.trays, p.recipe.bakeMinutes, fmt(p.order.dueTime),
                    p.slot.oven.id, p.slot.oven.code, p.slot.oven.capacity,
                    fmt(p.estStart), fmt(p.estFinish),
                    p.deadlineRisk, p.capacityNote, p.reason,
                    origin == null ? null : origin.id,
                    origin == null ? null : origin.code,
                    origin == null ? null : origin.status);
        }).toList();

        List<UnscheduledItemView> unscheduled = cs.unscheduled.stream().map(u -> {
            Oven origin = u.order.ovenId == null ? null : ovenMap.get(u.order.ovenId);
            return new UnscheduledItemView(
                    u.order.id, u.order.recipeId, u.recipe == null ? "?" : u.recipe.name,
                    u.order.trays, u.recipe == null ? null : u.recipe.bakeMinutes, fmt(u.order.dueTime),
                    u.reasonType, u.reason,
                    origin == null ? null : origin.id,
                    origin == null ? null : origin.code,
                    origin == null ? null : origin.status);
        }).toList();

        Map<Long, Long> assigned = cs.scheduled.stream()
                .collect(Collectors.groupingBy(p -> p.slot.oven.id, Collectors.counting()));
        List<ScheduleOvenView> ovens = cs.slots.stream().map(s -> new ScheduleOvenView(
                s.oven.id, s.oven.code, s.oven.capacity, s.oven.status,
                s.usable, s.usable ? fmt(s.freeAt) : null, s.note,
                assigned.getOrDefault(s.oven.id, 0L).intValue())).toList();

        List<LockedOrderView> locked = lockedViews(cs.allOrders, recipeMap, ovenMap);

        return new ScheduleView(saved, historical,
                v == null ? null : v.id,
                v == null ? null : v.versionNo,
                v == null ? null : v.baseVersionNo,
                latestVersionNo,
                v == null ? null : v.operatorName,
                v == null ? null : fmt(v.createdAt),
                stale, staleReasons, scheduled, unscheduled, locked, ovens, cs.pendingCount);
    }

    private ScheduleView toViewFromVersion(ScheduleVersion v, List<ScheduleItem> items, Integer latestVersionNo) {
        List<Oven> ovens = ovenRepository.findAll().stream()
                .sorted(Comparator.comparing(o -> o.id)).toList();
        List<BakeOrder> allOrders = orderRepository.findAll();
        List<BreadRecipe> recipes = recipeRepository.findAll();
        Map<Long, BreadRecipe> recipeMap = recipes.stream().collect(Collectors.toMap(r -> r.id, r -> r));
        Map<Long, Oven> ovenMap = ovens.stream().collect(Collectors.toMap(o -> o.id, o -> o));
        Map<Long, BakeOrder> orderMap = allOrders.stream().collect(Collectors.toMap(o -> o.id, o -> o));

        // 过期判定：保存时的输入指纹 vs 当前输入指纹
        Snapshot now = snapshot(ovens, allOrders, recipes);
        boolean stale = !now.fingerprint().equals(v.inputFingerprint);
        List<String> reasons = stale ? diffSnapshots(v.inputSnapshot, now.json()) : List.of();

        List<ScheduleItem> placedItems = items.stream()
                .filter(i -> Boolean.TRUE.equals(i.placed))
                .sorted(Comparator.comparing(i -> i.seqNo))
                .toList();
        List<ScheduleItem> unplacedItems = items.stream()
                .filter(i -> !Boolean.TRUE.equals(i.placed))
                .sorted(Comparator.comparing(i -> i.id))
                .toList();

        List<ScheduledItemView> scheduled = placedItems.stream().map(it -> {
            BakeOrder o = orderMap.get(it.orderId);
            BreadRecipe r = o == null ? null : recipeMap.get(o.recipeId);
            Oven origin = o != null && o.ovenId != null ? ovenMap.get(o.ovenId) : null;
            Oven assignedOven = it.ovenId == null ? null : ovenMap.get(it.ovenId);
            return new ScheduledItemView(it.seqNo, it.orderId,
                    o == null ? null : o.recipeId, r == null ? "?" : r.name,
                    o == null ? null : o.trays, r == null ? null : r.bakeMinutes, fmt(it.dueTime),
                    it.ovenId, it.ovenCode, assignedOven == null ? null : assignedOven.capacity,
                    fmt(it.estStartTime), fmt(it.estFinishTime),
                    it.deadlineRisk, it.capacityNote, it.reason,
                    origin == null ? null : origin.id,
                    origin == null ? null : origin.code,
                    origin == null ? null : origin.status);
        }).toList();

        List<UnscheduledItemView> unscheduled = unplacedItems.stream().map(it -> {
            BakeOrder o = orderMap.get(it.orderId);
            BreadRecipe r = o == null ? null : recipeMap.get(o.recipeId);
            Oven origin = o != null && o.ovenId != null ? ovenMap.get(o.ovenId) : null;
            return new UnscheduledItemView(it.orderId,
                    o == null ? null : o.recipeId, r == null ? "?" : r.name,
                    o == null ? null : o.trays, r == null ? null : r.bakeMinutes, fmt(it.dueTime),
                    it.reasonType, it.reason,
                    origin == null ? null : origin.id,
                    origin == null ? null : origin.code,
                    origin == null ? null : origin.status);
        }).toList();

        // 烤箱面板：实时可用性 + 本版排入单数
        Map<Long, LocalDateTime> busyUntil = busyUntil(allOrders, recipeMap);
        Map<Long, Long> assigned = placedItems.stream().filter(i -> i.ovenId != null)
                .collect(Collectors.groupingBy(i -> i.ovenId, Collectors.counting()));
        LocalDateTime nowTs = LocalDateTime.now();
        List<ScheduleOvenView> ovenViews = ovens.stream().map(ov -> {
            OvenSlot st = slotState(ov, busyUntil, nowTs);
            return new ScheduleOvenView(ov.id, ov.code, ov.capacity, ov.status, st.usable,
                    st.usable ? fmt(st.freeAt) : null, st.note,
                    assigned.getOrDefault(ov.id, 0L).intValue());
        }).toList();

        List<LockedOrderView> locked = lockedViews(allOrders, recipeMap, ovenMap);
        int pendingCount = (int) allOrders.stream().filter(o -> "待烤".equals(o.status)).count();

        return new ScheduleView(true, !Objects.equals(v.versionNo, latestVersionNo),
                v.id, v.versionNo, v.baseVersionNo, latestVersionNo,
                v.operatorName, fmt(v.createdAt), stale, reasons,
                scheduled, unscheduled, locked, ovenViews, pendingCount);
    }

    /** 已开始或已完成的工单：只读锁定展示，不参与排产 */
    private List<LockedOrderView> lockedViews(List<BakeOrder> allOrders,
                                              Map<Long, BreadRecipe> recipeMap,
                                              Map<Long, Oven> ovenMap) {
        return allOrders.stream()
                .filter(o -> !"待烤".equals(o.status))
                .sorted(Comparator.comparing(o -> o.id))
                .map(o -> {
                    BreadRecipe r = recipeMap.get(o.recipeId);
                    Oven oven = o.ovenId == null ? null : ovenMap.get(o.ovenId);
                    return new LockedOrderView(o.id, o.status, r == null ? "?" : r.name, o.trays,
                            oven == null ? "?" : oven.code, fmt(o.startTime));
                })
                .toList();
    }

    private Integer latestVersionNo() {
        return stateRepository.findById(1).map(s -> s.latestVersionNo).orElse(0);
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? null : FMT.format(t);
    }

    // ================= 输入快照 / 指纹 / 过期原因 =================

    /** 排产输入快照：烤箱状态/容量 + 待烤与烘焙中工单 + 配方耗时，序列化后取 SHA-256 作指纹 */
    private Snapshot snapshot(List<Oven> ovens, List<BakeOrder> allOrders, List<BreadRecipe> recipes) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("ovens", ovens.stream().sorted(Comparator.comparing(o -> o.id)).map(o -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", o.id);
            m.put("code", o.code);
            m.put("status", o.status);
            m.put("capacity", o.capacity);
            return m;
        }).toList());
        snap.put("orders", allOrders.stream()
                .filter(o -> "待烤".equals(o.status) || "烘焙中".equals(o.status))
                .sorted(Comparator.comparing(o -> o.id))
                .map(o -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("id", o.id);
                    m.put("status", o.status);
                    m.put("ovenId", o.ovenId);
                    m.put("recipeId", o.recipeId);
                    m.put("trays", o.trays);
                    m.put("due", o.dueTime == null ? null : FMT.format(o.dueTime));
                    m.put("start", o.startTime == null ? null : FMT.format(o.startTime));
                    return m;
                }).toList());
        snap.put("recipes", recipes.stream().sorted(Comparator.comparing(r -> r.id)).map(r -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", r.id);
            m.put("name", r.name);
            m.put("bakeMinutes", r.bakeMinutes);
            m.put("needTrays", r.needTrays);
            m.put("status", r.status);
            return m;
        }).toList());
        try {
            String json = objectMapper.writeValueAsString(snap);
            return new Snapshot(json, sha256(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("排产输入快照序列化失败", e);
        }
    }

    /** 过期原因：逐条对比保存时快照与当前快照，输出人话版变化明细 */
    private List<String> diffSnapshots(String oldJson, String newJson) {
        try {
            Map<String, List<Map<String, Object>>> oldSnap =
                    objectMapper.readValue(oldJson, new TypeReference<>() { });
            Map<String, List<Map<String, Object>>> newSnap =
                    objectMapper.readValue(newJson, new TypeReference<>() { });
            List<String> reasons = new ArrayList<>();
            diffOvens(index(oldSnap.get("ovens")), index(newSnap.get("ovens")), reasons);
            diffOrders(index(oldSnap.get("orders")), index(newSnap.get("orders")), reasons);
            diffRecipes(index(oldSnap.get("recipes")), index(newSnap.get("recipes")), reasons);
            if (reasons.isEmpty()) {
                reasons.add("排产输入数据已变化");
            }
            if (reasons.size() > 8) {
                int more = reasons.size() - 8;
                reasons = new ArrayList<>(reasons.subList(0, 8));
                reasons.add("…另有 " + more + " 项变化");
            }
            return reasons;
        } catch (Exception e) {
            return List.of("排产输入数据已变化");
        }
    }

    private Map<Long, Map<String, Object>> index(List<Map<String, Object>> rows) {
        Map<Long, Map<String, Object>> map = new LinkedHashMap<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                map.put(((Number) row.get("id")).longValue(), row);
            }
        }
        return map;
    }

    private Set<Long> unionKeys(Map<Long, ?> a, Map<Long, ?> b) {
        Set<Long> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }

    private void diffOvens(Map<Long, Map<String, Object>> oldM, Map<Long, Map<String, Object>> newM,
                           List<String> reasons) {
        for (Long id : unionKeys(oldM, newM)) {
            Map<String, Object> o = oldM.get(id);
            Map<String, Object> n = newM.get(id);
            if (o == null) {
                reasons.add("新增烤箱 " + n.get("code"));
                continue;
            }
            if (n == null) {
                reasons.add("烤箱 " + o.get("code") + " 已删除");
                continue;
            }
            if (!Objects.equals(o.get("status"), n.get("status"))) {
                reasons.add("烤箱 " + n.get("code") + " 状态：" + o.get("status") + " → " + n.get("status"));
            }
            if (!Objects.equals(o.get("capacity"), n.get("capacity"))) {
                reasons.add("烤箱 " + n.get("code") + " 容量：" + o.get("capacity") + " → " + n.get("capacity") + " 盘");
            }
        }
    }

    private void diffOrders(Map<Long, Map<String, Object>> oldM, Map<Long, Map<String, Object>> newM,
                            List<String> reasons) {
        for (Long id : unionKeys(oldM, newM)) {
            Map<String, Object> o = oldM.get(id);
            Map<String, Object> n = newM.get(id);
            if (o == null) {
                reasons.add("新增待烤/烘焙中工单 #" + id);
                continue;
            }
            if (n == null) {
                reasons.add("工单 #" + id + " 已出炉或删除");
                continue;
            }
            if (!Objects.equals(o.get("status"), n.get("status"))) {
                reasons.add("工单 #" + id + " 状态：" + o.get("status") + " → " + n.get("status"));
            }
            if (!Objects.equals(o.get("trays"), n.get("trays"))) {
                reasons.add("工单 #" + id + " 烤盘数：" + o.get("trays") + " → " + n.get("trays"));
            }
            if (!Objects.equals(o.get("due"), n.get("due"))) {
                reasons.add("工单 #" + id + " 交付截止：" + str(o.get("due")) + " → " + str(n.get("due")));
            }
            if (!Objects.equals(o.get("ovenId"), n.get("ovenId"))) {
                reasons.add("工单 #" + id + " 所属烤箱变化（含故障接管迁移）");
            }
        }
    }

    private void diffRecipes(Map<Long, Map<String, Object>> oldM, Map<Long, Map<String, Object>> newM,
                             List<String> reasons) {
        for (Long id : unionKeys(oldM, newM)) {
            Map<String, Object> o = oldM.get(id);
            Map<String, Object> n = newM.get(id);
            if (o == null) {
                reasons.add("新增配方 " + n.get("name"));
                continue;
            }
            if (n == null) {
                reasons.add("配方 " + o.get("name") + " 已删除");
                continue;
            }
            if (!Objects.equals(o.get("bakeMinutes"), n.get("bakeMinutes"))) {
                reasons.add("配方 " + n.get("name") + " 烘焙时长：" + o.get("bakeMinutes") + " → " + n.get("bakeMinutes") + " 分钟");
            }
            if (!Objects.equals(o.get("needTrays"), n.get("needTrays"))) {
                reasons.add("配方 " + n.get("name") + " 需用烤盘：" + o.get("needTrays") + " → " + n.get("needTrays"));
            }
            if (!Objects.equals(o.get("status"), n.get("status"))) {
                reasons.add("配方 " + n.get("name") + " 状态：" + o.get("status") + " → " + n.get("status"));
            }
        }
    }

    private static String str(Object v) {
        return v == null ? "无" : String.valueOf(v);
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
