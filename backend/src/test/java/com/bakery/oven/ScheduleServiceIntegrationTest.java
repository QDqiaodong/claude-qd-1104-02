package com.bakery.oven;

import com.bakery.oven.common.BizException;
import com.bakery.oven.common.ScheduleConflictException;
import com.bakery.oven.dto.SchedulePreviewRequest;
import com.bakery.oven.dto.ScheduleSaveRequest;
import com.bakery.oven.dto.ScheduleView;
import com.bakery.oven.entity.BakeOrder;
import com.bakery.oven.entity.Oven;
import com.bakery.oven.repository.BakeOrderRepository;
import com.bakery.oven.repository.OvenRepository;
import com.bakery.oven.service.ScheduleService;
import com.bakery.oven.service.ScheduleService.ComputedSchedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生产排产：建议顺序计算、未排入区口径、版本保存与并发、过期检测。
 * 计算类用例用固定 now（2026-10-07 10:00）保证时间断言确定；
 * 保存/并发用例走真实事务与行锁。
 */
@SpringBootTest
class ScheduleServiceIntegrationTest {

    @Autowired ScheduleService scheduleService;
    @Autowired OvenRepository ovenRepository;
    @Autowired BakeOrderRepository orderRepository;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 10, 0, 0);

    private Oven oven(long id) {
        return ovenRepository.findById(id).orElseThrow();
    }

    private BakeOrder order(long id) {
        return orderRepository.findById(id).orElseThrow();
    }

    /** 每个用例前恢复基础数据（H2 MySQL 模式）：5 炉 3 配方，工单/排产表清空，版本计数归零 */
    @BeforeEach
    void resetData() {
        new TransactionTemplate(txManager).executeWithoutResult((TransactionStatus s) -> {
            em.clear();
            jdbc.execute("SET REFERENTIAL_INTEGRITY = FALSE");
            jdbc.execute("DELETE FROM schedule_item");
            jdbc.execute("DELETE FROM schedule_version");
            jdbc.execute("DELETE FROM schedule_state");
            jdbc.execute("DELETE FROM shipment");
            jdbc.execute("DELETE FROM bake_order");
            jdbc.execute("DELETE FROM bread_recipe");
            jdbc.execute("DELETE FROM oven");
            jdbc.execute("ALTER TABLE oven ALTER COLUMN id RESTART WITH 1");
            jdbc.execute("ALTER TABLE bread_recipe ALTER COLUMN id RESTART WITH 1");
            jdbc.execute("ALTER TABLE bake_order ALTER COLUMN id RESTART WITH 1");
            jdbc.execute("ALTER TABLE schedule_version ALTER COLUMN id RESTART WITH 1");
            jdbc.execute("ALTER TABLE schedule_item ALTER COLUMN id RESTART WITH 1");
            jdbc.batchUpdate(
                "INSERT INTO oven (id, code, capacity, status, created_at) VALUES (1,'OV-01',4,'空闲',NULL),"
                    + "(2,'OV-02',6,'占用',NULL),(3,'OV-03',3,'故障',NULL),"
                    + "(4,'OV-04',5,'空闲',NULL),(5,'OV-05',2,'空闲',NULL)");
            jdbc.batchUpdate(
                "INSERT INTO bread_recipe (id, name, need_trays, bake_minutes, status, created_at) VALUES "
                    + "(1,'法棍',2,30,'上架',NULL),(2,'牛角包',3,25,'上架',NULL),(3,'乡村面包',4,45,'上架',NULL)");
            jdbc.update("INSERT INTO schedule_state (id, latest_version_no) VALUES (1, 0)");
            jdbc.execute("SET REFERENTIAL_INTEGRITY = TRUE");
            em.clear();
        });
    }

    private void insertOrder(long id, long ovenId, long recipeId, int trays,
                             String start, String due, String status) {
        jdbc.update("INSERT INTO bake_order (id, oven_id, recipe_id, trays, start_time, due_time, status, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,NULL)",
                id, ovenId, recipeId, trays, start, due, status);
    }

    // ================= 计算：默认排序与跨烤箱分配 =================

    /** 默认排序=交付截止早优先；同刻空闲选最小可容纳炉；占满整炉有容量说明 */
    @Test
    void compute_defaultSort_assignsByDueThenBestFit() {
        insertOrder(10, 1, 1, 2, null, "2026-10-07 12:00:00", "待烤"); // 法棍 2盘/30分，截止12:00
        insertOrder(11, 4, 3, 4, null, "2026-10-07 11:00:00", "待烤"); // 乡村 4盘/45分，截止11:00
        insertOrder(12, 1, 2, 3, null, null, "待烤");                  // 牛角 3盘/25分，无截止
        insertOrder(3, 2, 1, 2, "2026-10-07 08:00:00", null, "烘焙中"); // OV-02 占用来源，08:30 已释放

        ComputedSchedule cs = scheduleService.compute(null, NOW);

        assertEquals(3, cs.scheduled.size());
        assertEquals(0, cs.unscheduled.size());
        // 顺序：11(截止11:00) → 10(截止12:00) → 12(无截止靠后)
        assertEquals(11L, cs.scheduled.get(0).order.id);
        assertEquals(10L, cs.scheduled.get(1).order.id);
        assertEquals(12L, cs.scheduled.get(2).order.id);
        assertEquals(1, cs.scheduled.get(0).seq);
        assertEquals(2, cs.scheduled.get(1).seq);
        assertEquals(3, cs.scheduled.get(2).seq);
        // 11：4盘 → 同刻空闲的最小可容纳炉 OV-01(4)，10:00-10:45
        assertEquals("OV-01", cs.scheduled.get(0).slot.oven.code);
        assertEquals(LocalDateTime.of(2026, 10, 7, 10, 0), cs.scheduled.get(0).estStart);
        assertEquals(LocalDateTime.of(2026, 10, 7, 10, 45), cs.scheduled.get(0).estFinish);
        // 10：2盘 → OV-05(2) 最小可容纳，10:00-10:30，占满整炉
        assertEquals("OV-05", cs.scheduled.get(1).slot.oven.code);
        assertTrue(cs.scheduled.get(1).capacityNote.contains("占满整炉"), cs.scheduled.get(1).capacityNote);
        // 12：3盘 → OV-02(6)/OV-04(5) 同刻空闲 → 最小可容纳 OV-04，10:00-10:25
        assertEquals("OV-04", cs.scheduled.get(2).slot.oven.code);
        assertEquals(LocalDateTime.of(2026, 10, 7, 10, 25), cs.scheduled.get(2).estFinish);
        // 可解释原因非空
        assertTrue(cs.scheduled.get(0).reason.contains("第 1 顺位"), cs.scheduled.get(0).reason);
    }

    /** 占用中的烤箱要等炉上烘焙中工单出炉才释放，后排工单排到释放时刻 */
    @Test
    void compute_occupiedOven_waitsForBakingFinish() {
        insertOrder(3, 2, 1, 2, "2026-10-07 09:50:00", null, "烘焙中"); // OV-02 预计 10:20 释放
        insertOrder(10, 2, 3, 6, null, null, "待烤"); // 6盘，只有 OV-02(容量6) 装得下

        ComputedSchedule cs = scheduleService.compute(null, NOW);

        assertEquals(1, cs.scheduled.size());
        assertEquals("OV-02", cs.scheduled.get(0).slot.oven.code);
        assertEquals(LocalDateTime.of(2026, 10, 7, 10, 20), cs.scheduled.get(0).estStart);
        assertEquals(LocalDateTime.of(2026, 10, 7, 11, 5), cs.scheduled.get(0).estFinish);
    }

    /** 预计逾期的工单留在未排入区并说明原因，且不占用炉位（后续工单仍按原释放时刻排入） */
    @Test
    void compute_overdue_staysUnscheduledAndDoesNotConsumeSlot() {
        insertOrder(10, 1, 1, 2, null, "2026-10-07 10:10:00", "待烤"); // 最早 10:30 完成 > 截止 10:10
        insertOrder(11, 1, 1, 2, null, null, "待烤");

        ComputedSchedule cs = scheduleService.compute(null, NOW);

        assertEquals(1, cs.unscheduled.size());
        assertEquals(10L, cs.unscheduled.get(0).order.id);
        assertEquals("OVERDUE", cs.unscheduled.get(0).reasonType);
        assertTrue(cs.unscheduled.get(0).reason.contains("预计逾期"), cs.unscheduled.get(0).reason);
        // 逾期单不占炉：11 仍能 10:00 立即开烤
        assertEquals(1, cs.scheduled.size());
        assertEquals(11L, cs.scheduled.get(0).order.id);
        assertEquals(LocalDateTime.of(2026, 10, 7, 10, 0), cs.scheduled.get(0).estStart);
    }

    /** 工单烤盘数超过全部可用烤箱容量 → 未排入区「容量预警」 */
    @Test
    void compute_capacityInsufficient_staysUnscheduled() {
        insertOrder(3, 2, 1, 2, "2026-10-07 09:50:00", null, "烘焙中"); // OV-02 可用（10:20 释放）
        insertOrder(10, 2, 3, 7, null, null, "待烤"); // 7盘 > 可用最大容量 6

        ComputedSchedule cs = scheduleService.compute(null, NOW);

        assertEquals(0, cs.scheduled.size());
        assertEquals(1, cs.unscheduled.size());
        assertEquals("CAPACITY", cs.unscheduled.get(0).reasonType);
        assertTrue(cs.unscheduled.get(0).reason.contains("容量预警"), cs.unscheduled.get(0).reason);
    }

    /** 全部烤箱不可用 → 未排入区「无可用烤箱」 */
    @Test
    void compute_noUsableOven_staysUnscheduled() {
        jdbc.update("UPDATE oven SET status='故障'");
        insertOrder(10, 1, 1, 2, null, null, "待烤");

        ComputedSchedule cs = scheduleService.compute(null, NOW);

        assertEquals(0, cs.scheduled.size());
        assertEquals(1, cs.unscheduled.size());
        assertEquals("NO_OVEN", cs.unscheduled.get(0).reasonType);
        assertTrue(cs.unscheduled.get(0).reason.contains("无可用烤箱"), cs.unscheduled.get(0).reason);
    }

    /** 操作员手动优先级覆盖默认排序；已开始/不存在/重复的工单被拒绝 */
    @Test
    void compute_manualPriority_respected_andValidated() {
        insertOrder(10, 1, 1, 2, null, "2026-10-07 11:00:00", "待烤");
        insertOrder(11, 1, 1, 2, null, "2026-10-07 12:00:00", "待烤");
        insertOrder(12, 2, 1, 2, "2026-10-07 09:00:00", null, "烘焙中");

        // 默认 10 在前；手动把 11 调前 → 11 先占最小可容纳炉 OV-05
        ComputedSchedule cs = scheduleService.compute(List.of(11L, 10L), NOW);
        assertEquals(11L, cs.scheduled.get(0).order.id);
        assertEquals(10L, cs.scheduled.get(1).order.id);
        assertEquals("OV-05", cs.scheduled.get(0).slot.oven.code);

        BizException started = assertThrows(BizException.class,
                () -> scheduleService.compute(List.of(12L), NOW));
        assertTrue(started.getMessage().contains("已开始或已完成"), started.getMessage());
        assertThrows(BizException.class, () -> scheduleService.compute(List.of(999L), NOW));
        assertThrows(BizException.class, () -> scheduleService.compute(List.of(10L, 10L), NOW));
    }

    // ================= 保存：只影响建议，不改写工单/烤箱 =================

    /** 保存生成版本与明细；工单状态、挂炉归属、烤箱状态全部保持原样 */
    @Test
    void save_persistsVersion_andNeverTouchesOrdersOrOvens() {
        insertOrder(10, 1, 1, 2, null, "2026-10-07 12:00:00", "待烤");
        insertOrder(11, 4, 3, 4, null, null, "待烤");

        ScheduleView v1 = scheduleService.save(new ScheduleSaveRequest(null, "张三", null));
        assertTrue(v1.saved());
        assertEquals(1, v1.versionNo());
        assertEquals(2, v1.scheduled().size());
        assertEquals(0, v1.unscheduled().size());
        assertFalse(v1.stale());
        assertEquals("张三", v1.operatorName());
        // 每条建议都带预计时间、占用烤箱、容量说明与原因
        var first = v1.scheduled().get(0);
        assertNotNull(first.estStartTime());
        assertNotNull(first.estFinishTime());
        assertNotNull(first.ovenCode());
        assertNotNull(first.capacityNote());
        assertNotNull(first.reason());

        // 工单与烤箱不被排产改写
        assertEquals("待烤", order(10).status);
        assertEquals(1L, order(10).ovenId);
        assertNull(order(10).startTime);
        assertEquals("空闲", oven(1L).status);
        assertEquals("空闲", oven(4L).status);

        // 再保存 → 版本 2，手动顺序生效；版本 1 成为历史
        ScheduleView v2 = scheduleService.save(new ScheduleSaveRequest(1L, "李四", List.of(11L, 10L)));
        assertEquals(2, v2.versionNo());
        assertEquals(11L, v2.scheduled().get(0).orderId());
        assertEquals(2, scheduleService.versions().size());
        assertTrue(scheduleService.version(v1.versionId()).historical());
        assertFalse(scheduleService.current().historical());
    }

    /** 基于过期版本号（或未基于任何版本）的保存被拒绝，全库只保留一个版本 */
    @Test
    void save_staleBase_rejected() {
        insertOrder(10, 1, 1, 2, null, null, "待烤");
        scheduleService.save(new ScheduleSaveRequest(null, "甲", null));

        assertThrows(ScheduleConflictException.class,
                () -> scheduleService.save(new ScheduleSaveRequest(null, "乙", null)));
        assertThrows(ScheduleConflictException.class,
                () -> scheduleService.save(new ScheduleSaveRequest(0L, "乙", null)));
        assertEquals(1, scheduleService.versions().size());
    }

    /** 已开始/已完成的工单不能进入优先级序列（对应前端“不可拖动”），整单不保存 */
    @Test
    void save_rejectsStartedOrderInPriorities() {
        insertOrder(10, 1, 1, 2, null, null, "待烤");
        insertOrder(11, 2, 1, 2, "2026-10-07 09:00:00", null, "烘焙中");

        BizException ex = assertThrows(BizException.class,
                () -> scheduleService.save(new ScheduleSaveRequest(null, "甲", List.of(10L, 11L))));
        assertTrue(ex.getMessage().contains("已开始或已完成"), ex.getMessage());
        assertEquals(0, scheduleService.versions().size());
    }

    /** 并发：两名操作员同时基于同一版本保存，行锁串行裁决，只保留一个版本 */
    @Test
    void concurrentSave_onlyOneVersionKept() throws Exception {
        insertOrder(10, 1, 1, 2, null, null, "待烤");
        insertOrder(11, 4, 1, 2, null, null, "待烤");
        scheduleService.save(new ScheduleSaveRequest(null, "初始", null));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> f1 = pool.submit(() -> {
            try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            scheduleService.save(new ScheduleSaveRequest(1L, "甲", List.of(10L, 11L)));
        });
        Future<?> f2 = pool.submit(() -> {
            try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            scheduleService.save(new ScheduleSaveRequest(1L, "乙", List.of(11L, 10L)));
        });
        start.countDown();

        int ok = 0, conflict = 0;
        for (Future<?> f : List.of(f1, f2)) {
            try {
                f.get(30, TimeUnit.SECONDS);
                ok++;
            } catch (ExecutionException e) {
                assertInstanceOf(ScheduleConflictException.class, e.getCause());
                conflict++;
            }
        }
        pool.shutdown();

        assertEquals(1, ok, "恰好一人保存成功");
        assertEquals(1, conflict, "另一人收到保存冲突");
        // 只保留一个最新版本：共 2 个版本（初始 + 胜出者），current 即为胜出顺序
        assertEquals(2, scheduleService.versions().size());
        ScheduleView cur = scheduleService.current();
        assertEquals(2, cur.versionNo());
        assertFalse(cur.stale());
    }

    /** 较慢的旧计算不能覆盖新版本：甲基于 v1 的保存晚于乙的 v2 到达 → 冲突，v2 原样保留 */
    @Test
    void slowOldSave_cannotOverwriteNewerVersion() {
        insertOrder(10, 1, 1, 2, null, null, "待烤");
        insertOrder(11, 4, 1, 2, null, null, "待烤");
        scheduleService.save(new ScheduleSaveRequest(null, "初始", null)); // v1

        // 乙先保存 v2（顺序 11,10）
        scheduleService.save(new ScheduleSaveRequest(1L, "乙", List.of(11L, 10L)));
        // 甲基于 v1 的慢计算随后才到 → 冲突
        ScheduleConflictException ex = assertThrows(ScheduleConflictException.class,
                () -> scheduleService.save(new ScheduleSaveRequest(1L, "甲", List.of(10L, 11L))));
        assertTrue(ex.getMessage().contains("#2"), ex.getMessage());

        ScheduleView cur = scheduleService.current();
        assertEquals(2, cur.versionNo());
        assertEquals(11L, cur.scheduled().get(0).orderId(), "仍是乙保存的顺序，未被旧计算覆盖");
    }

    // ================= 过期：输入数据变化 → 旧版本标过期，可基于最新数据重算 =================

    /** 烤箱状态变化（置故障）→ 旧版本过期并列出变化；重算后新炉分配生效，再保存不过期 */
    @Test
    void stale_whenOvenBroken_andRecomputeUsesLatestData() {
        insertOrder(10, 1, 3, 4, null, null, "待烤"); // 4盘 → 默认排 OV-01
        ScheduleView v1 = scheduleService.save(new ScheduleSaveRequest(null, "甲", null));
        assertEquals("OV-01", v1.scheduled().get(0).ovenCode());

        jdbc.update("UPDATE oven SET status='故障' WHERE id=1");
        ScheduleView cur = scheduleService.current();
        assertTrue(cur.stale());
        assertTrue(cur.staleReasons().stream().anyMatch(r -> r.contains("OV-01") && r.contains("故障")),
                cur.staleReasons().toString());

        // 基于最新数据重算：OV-01 故障 → 改挂 OV-04（OV-02 占用不可预计、OV-03 故障、OV-05 容量不足）
        ScheduleView preview = scheduleService.preview(new SchedulePreviewRequest(null));
        assertFalse(preview.saved());
        assertEquals("OV-04", preview.scheduled().get(0).ovenCode());

        // 保存重算结果 → 版本 2 不过期
        scheduleService.save(new ScheduleSaveRequest(1L, "甲", null));
        assertFalse(scheduleService.current().stale());
    }

    /** 工单烤盘数 / 交付截止变化 → 旧版本过期并逐条列出原因 */
    @Test
    void stale_whenTraysOrDueChanged() {
        insertOrder(10, 1, 1, 2, null, "2026-10-07 12:00:00", "待烤");
        scheduleService.save(new ScheduleSaveRequest(null, "甲", null));

        jdbc.update("UPDATE bake_order SET trays=3, due_time='2026-10-07 18:00:00' WHERE id=10");
        ScheduleView cur = scheduleService.current();
        assertTrue(cur.stale());
        assertTrue(cur.staleReasons().stream().anyMatch(r -> r.contains("烤盘数")),
                cur.staleReasons().toString());
        assertTrue(cur.staleReasons().stream().anyMatch(r -> r.contains("交付截止")),
                cur.staleReasons().toString());
    }

    /** 待烤工单开始烘焙（状态流转）→ 旧版本过期；未排入/锁定工单口径正确 */
    @Test
    void stale_whenOrderStarted_andLockedOrdersListed() {
        insertOrder(10, 1, 1, 2, null, null, "待烤");
        insertOrder(11, 2, 1, 2, "2026-10-07 09:00:00", null, "烘焙中");
        ScheduleView v1 = scheduleService.save(new ScheduleSaveRequest(null, "甲", null));
        // 烘焙中工单出现在锁定区，不参与排产
        assertEquals(1, v1.locked().size());
        assertEquals(11L, v1.locked().get(0).orderId());

        jdbc.update("UPDATE bake_order SET status='烘焙中', start_time='2026-10-07 10:00:00' WHERE id=10");
        ScheduleView cur = scheduleService.current();
        assertTrue(cur.stale());
        assertTrue(cur.staleReasons().stream().anyMatch(r -> r.contains("#10") && r.contains("烘焙中")),
                cur.staleReasons().toString());
    }

    /** 没有任何已保存版本时，current 返回基于最新数据的未保存预览 */
    @Test
    void current_withoutVersion_returnsUnsavedPreview() {
        insertOrder(10, 1, 1, 2, null, null, "待烤");
        ScheduleView cur = scheduleService.current();
        assertFalse(cur.saved());
        assertNull(cur.versionNo());
        assertEquals(1, cur.scheduled().size());
        assertFalse(cur.stale());
        assertEquals(1, cur.pendingCount());
    }
}
