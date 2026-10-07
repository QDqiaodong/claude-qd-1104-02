package com.bakery.oven;

import com.bakery.oven.common.BizException;
import com.bakery.oven.dto.ScheduleSaveRequest;
import com.bakery.oven.dto.ScheduleView;
import com.bakery.oven.entity.BakeOrder;
import com.bakery.oven.entity.BakeScheduleItem;
import com.bakery.oven.entity.Oven;
import com.bakery.oven.repository.BakeOrderRepository;
import com.bakery.oven.repository.BakeScheduleItemRepository;
import com.bakery.oven.repository.BakeScheduleMetaRepository;
import com.bakery.oven.repository.BakeScheduleRepository;
import com.bakery.oven.repository.OvenRepository;
import com.bakery.oven.service.ScheduleService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ScheduleServiceIntegrationTest {

    @Autowired ScheduleService scheduleService;
    @Autowired OvenRepository ovenRepository;
    @Autowired BakeOrderRepository orderRepository;
    @Autowired BakeScheduleRepository scheduleRepository;
    @Autowired BakeScheduleItemRepository itemRepository;
    @Autowired BakeScheduleMetaRepository metaRepository;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    private Oven oven(long id) {
        return ovenRepository.findById(id).orElseThrow();
    }

    private BakeOrder order(long id) {
        return orderRepository.findById(id).orElseThrow();
    }

    /**
     * 每个用例前恢复种子数据（H2 MySQL 模式）：
     * OV-01(4,空闲) OV-02(6,占用,烘焙中#2 预计 15 分钟后出炉) OV-03(3,故障) OV-04(5,空闲) OV-05(2,空闲)
     * 待烤：#1(法棍2盘,OV-03故障,+4h) #3(法棍2盘,OV-01,+2h) #4(乡村4盘,OV-04,+3h) #5(牛角包3盘,OV-01,+20min→必逾期)
     */
    @BeforeEach
    void resetData() {
        new TransactionTemplate(txManager).executeWithoutResult((TransactionStatus s) -> {
            em.clear();
            jdbc.execute("SET REFERENTIAL_INTEGRITY = FALSE");
            jdbc.execute("DELETE FROM bake_schedule_item");
            jdbc.execute("DELETE FROM bake_schedule");
            jdbc.execute("DELETE FROM shipment");
            jdbc.execute("DELETE FROM bake_order");
            jdbc.execute("DELETE FROM bread_recipe");
            jdbc.execute("DELETE FROM oven");
            jdbc.execute("UPDATE bake_schedule_meta SET latest_version_id = NULL WHERE id = 1");
            jdbc.execute("ALTER TABLE oven ALTER COLUMN id RESTART WITH 1");
            jdbc.execute("ALTER TABLE bread_recipe ALTER COLUMN id RESTART WITH 1");
            jdbc.execute("ALTER TABLE bake_order ALTER COLUMN id RESTART WITH 1");
            jdbc.execute("ALTER TABLE bake_schedule ALTER COLUMN id RESTART WITH 1");
            jdbc.batchUpdate(
                "INSERT INTO oven (id, code, capacity, status, created_at) VALUES (1,'OV-01',4,'空闲',NULL),"
                    + "(2,'OV-02',6,'占用',NULL),(3,'OV-03',3,'故障',NULL),"
                    + "(4,'OV-04',5,'空闲',NULL),(5,'OV-05',2,'空闲',NULL)");
            jdbc.batchUpdate(
                "INSERT INTO bread_recipe (id, name, need_trays, bake_minutes, status, created_at) VALUES "
                    + "(1,'法棍',2,30,'上架',NULL),(2,'牛角包',3,25,'上架',NULL),(3,'乡村面包',4,45,'上架',NULL)");
            jdbc.execute("SET REFERENTIAL_INTEGRITY = TRUE");
            em.clear();
        });
        // 时间相关的种子用参数化写入（相对当前时间）
        new TransactionTemplate(txManager).executeWithoutResult((TransactionStatus s) -> {
            jdbc.update("INSERT INTO bake_order (id, oven_id, recipe_id, trays, start_time, deadline, status, created_at) "
                            + "VALUES (1,3,1,2,NULL,?,'待烤',NULL)",
                    Timestamp.valueOf(LocalDateTime.now().plusHours(4)));
            jdbc.update("INSERT INTO bake_order (id, oven_id, recipe_id, trays, start_time, deadline, status, created_at) "
                            + "VALUES (2,2,2,3,?,?,'烘焙中',NULL)",
                    Timestamp.valueOf(LocalDateTime.now().minusMinutes(10)),
                    Timestamp.valueOf(LocalDateTime.now().plusHours(26)));
            jdbc.update("INSERT INTO bake_order (id, oven_id, recipe_id, trays, start_time, deadline, status, created_at) "
                            + "VALUES (3,1,1,2,NULL,?,'待烤',NULL)",
                    Timestamp.valueOf(LocalDateTime.now().plusHours(2)));
            jdbc.update("INSERT INTO bake_order (id, oven_id, recipe_id, trays, start_time, deadline, status, created_at) "
                            + "VALUES (4,4,3,4,NULL,?,'待烤',NULL)",
                    Timestamp.valueOf(LocalDateTime.now().plusHours(3)));
            jdbc.update("INSERT INTO bake_order (id, oven_id, recipe_id, trays, start_time, deadline, status, created_at) "
                            + "VALUES (5,1,2,3,NULL,?,'待烤',NULL)",
                    Timestamp.valueOf(LocalDateTime.now().plusMinutes(20)));
            em.clear();
        });
    }

    private ScheduleView previewDefault() {
        return scheduleService.preview(null);
    }

    /** 默认（按截止时间升序）：3 张排入、1 张预计逾期留未排入区，烤箱分配与预计时间合理 */
    @Test
    void compute_default_schedulesWithEstimatesAndRisk() {
        ScheduleView view = previewDefault();

        assertEquals(List.of(5L, 3L, 4L, 1L), view.priorities());
        assertEquals(3, view.scheduled().size());
        assertEquals(1, view.unscheduled().size());

        var s3 = view.scheduled().stream().filter(x -> x.orderId() == 3L).findFirst().orElseThrow();
        assertEquals("OV-01", s3.ovenCode());
        assertEquals(30, java.time.Duration.between(s3.estStart(), s3.estFinish()).toMinutes());
        assertFalse(s3.estFinish().isAfter(s3.deadline()));
        assertEquals("正常", s3.risk());
        assertEquals(2, s3.trays());
        assertEquals(4, s3.ovenCapacity());

        var s4 = view.scheduled().stream().filter(x -> x.orderId() == 4L).findFirst().orElseThrow();
        assertEquals("OV-04", s4.ovenCode());

        // 工单 #1 归属故障炉 OV-03：建议改挂 OV-05，但只是建议（ownOven 仍 OV-03）
        var s1 = view.scheduled().stream().filter(x -> x.orderId() == 1L).findFirst().orElseThrow();
        assertEquals("OV-05", s1.ovenCode());
        assertEquals("OV-03", s1.ownOvenCode());
        assertEquals("故障", s1.ownOvenStatus());
    }

    /** 预计逾期的工单必须留在未排入区并说明原因，不能伪装成已安排 */
    @Test
    void overdueOrder_staysUnscheduledWithReason() {
        ScheduleView view = previewDefault();
        assertTrue(view.scheduled().stream().noneMatch(x -> x.orderId() == 5L));
        var u5 = view.unscheduled().stream().filter(x -> x.orderId() == 5L).findFirst().orElseThrow();
        assertEquals("overdue", u5.reasonType());
        assertTrue(u5.reason().contains("预计逾期"), u5.reason());
        assertTrue(u5.reason().contains("交付截止时间"), u5.reason());
    }

    /** 排队导致的逾期可通过调整优先级挽救：提前后不再逾期 */
    @Test
    void reprioritize_canRescueQueuedOverdue() {
        // 只留 OV-01 可用，两张工单排队：先乡村(45min)则法棍(+50min 截止)逾期；先法棍则都能排入
        new TransactionTemplate(txManager).executeWithoutResult((TransactionStatus s) -> {
            jdbc.update("UPDATE oven SET status='故障' WHERE id IN (2,4,5)");
            jdbc.update("INSERT INTO bake_order (id, oven_id, recipe_id, trays, start_time, deadline, status, created_at) "
                            + "VALUES (10,1,3,4,NULL,?,'待烤',NULL)",
                    Timestamp.valueOf(LocalDateTime.now().plusHours(8)));
            jdbc.update("INSERT INTO bake_order (id, oven_id, recipe_id, trays, start_time, deadline, status, created_at) "
                            + "VALUES (11,1,1,2,NULL,?,'待烤',NULL)",
                    Timestamp.valueOf(LocalDateTime.now().plusMinutes(50)));
            em.clear();
        });

        ScheduleView countryFirst = scheduleService.preview(List.of(10L, 11L));
        assertTrue(countryFirst.unscheduled().stream().anyMatch(x -> x.orderId() == 11L
                && "overdue".equals(x.reasonType())));

        ScheduleView baguetteFirst = scheduleService.preview(List.of(11L, 10L));
        assertTrue(baguetteFirst.scheduled().stream().anyMatch(x -> x.orderId() == 11L));
        assertTrue(baguetteFirst.scheduled().stream().anyMatch(x -> x.orderId() == 10L));
    }

    /** 容量不足（唯一够大的烤箱故障）：留未排入区并给出容量预警 */
    @Test
    void capacityShortfall_staysUnscheduledWithCapacityWarning() {
        new TransactionTemplate(txManager).executeWithoutResult((TransactionStatus s) -> {
            jdbc.update("INSERT INTO bake_order (id, oven_id, recipe_id, trays, start_time, deadline, status, created_at) "
                            + "VALUES (20,2,1,6,NULL,?,'待烤',NULL)",
                    Timestamp.valueOf(LocalDateTime.now().plusHours(6)));
            jdbc.update("UPDATE oven SET status='故障' WHERE id=2");
            em.clear();
        });
        ScheduleView view = previewDefault();
        var u20 = view.unscheduled().stream().filter(x -> x.orderId() == 20L).findFirst().orElseThrow();
        assertEquals("capacity", u20.reasonType());
        assertTrue(u20.reason().contains("容量预警"), u20.reason());
        assertTrue(u20.reason().contains("6"), u20.reason());
        assertTrue(view.scheduled().stream().noneMatch(x -> x.orderId() == 20L));
    }

    /** 全部烤箱故障：没有可用烤箱，全部待烤留未排入区 */
    @Test
    void allOvensBroken_noAvailableOven() {
        new TransactionTemplate(txManager).executeWithoutResult((TransactionStatus s) -> {
            jdbc.update("UPDATE oven SET status='故障'");
            em.clear();
        });
        ScheduleView view = previewDefault();
        assertEquals(0, view.scheduled().size());
        assertEquals(4, view.unscheduled().size());
        assertTrue(view.unscheduled().stream().allMatch(x -> "no-oven".equals(x.reasonType())));
        assertTrue(view.unscheduled().get(0).reason().contains("没有可用烤箱"));
    }

    /** 已开始/已完成的工单不参与排产，也不能被保存进版本 */
    @Test
    void startedOrders_notSchedulableAndCannotBeSaved() {
        ScheduleView view = previewDefault();
        assertTrue(view.scheduled().stream().noneMatch(x -> x.orderId() == 2L));
        assertTrue(view.unscheduled().stream().noneMatch(x -> x.orderId() == 2L));

        BizException ex = assertThrows(BizException.class, () -> scheduleService.save(
                new ScheduleSaveRequest("t", "op", null, view.fingerprint(), List.of(1L, 2L, 3L, 4L, 5L))));
        assertTrue(ex.getMessage().contains("不能参与排产"), ex.getMessage());
        assertEquals(0, scheduleRepository.count());
    }

    /** 保存版本：落库版本头+明细快照，且不改写工单状态/归属与烤箱状态 */
    @Test
    void save_persistsVersion_andDoesNotMutateOrders() {
        ScheduleView pv = previewDefault();
        ScheduleView saved = scheduleService.save(
                new ScheduleSaveRequest("早班排产", "张三", null, pv.fingerprint(), pv.priorities()));

        assertNotNull(saved.versionId());
        assertFalse(saved.stale());
        assertEquals("早班排产", saved.versionName());
        assertEquals(1, scheduleRepository.count());
        assertEquals(1L, metaRepository.findById(1L).orElseThrow().latestVersionId);

        // 明细快照：优先级顺序与计算结果（含未排入原因）落库
        List<BakeScheduleItem> items = itemRepository.findByScheduleIdOrderBySeq(saved.versionId());
        assertEquals(4, items.size());
        assertEquals(5L, items.get(0).orderId);
        assertFalse(items.get(0).scheduled);
        assertTrue(items.get(0).reason.contains("预计逾期"));
        assertEquals(3L, items.get(1).orderId);
        assertTrue(items.get(1).scheduled);
        assertEquals("OV-01", items.get(1).ovenCode);
        assertNotNull(items.get(1).estStart);
        assertNotNull(items.get(1).estFinish);

        // 排产只是建议：工单状态/归属、烤箱状态均不被改写
        assertEquals("待烤", order(1).status);
        assertEquals(3L, order(1).ovenId);
        assertEquals("烘焙中", order(2).status);
        assertEquals(2L, order(2).ovenId);
        assertEquals("占用", oven(2).status);
        assertEquals("故障", oven(3).status);
    }

    /** 数据变化后基于旧指纹保存（较慢的旧计算）→ 拒绝，不能覆盖 */
    @Test
    void saveWithStaleFingerprint_rejected() {
        ScheduleView pv = previewDefault();
        new TransactionTemplate(txManager).executeWithoutResult((TransactionStatus s) -> {
            jdbc.update("UPDATE bake_order SET trays=3 WHERE id=3");
            em.clear();
        });
        BizException ex = assertThrows(BizException.class, () -> scheduleService.save(
                new ScheduleSaveRequest("t", "op", null, pv.fingerprint(), pv.priorities())));
        assertTrue(ex.getMessage().contains("重算"), ex.getMessage());
        assertEquals(0, scheduleRepository.count());
    }

    /** 基于旧版本号保存（他人已保存更新版本）→ 拒绝，只保留一个版本 */
    @Test
    void saveWithStaleBase_rejected() {
        ScheduleView pv = previewDefault();
        scheduleService.save(new ScheduleSaveRequest("v1", "张三", null, pv.fingerprint(), pv.priorities()));

        ScheduleView fresh = previewDefault();
        BizException ex = assertThrows(BizException.class, () -> scheduleService.save(
                new ScheduleSaveRequest("v2", "李四", null, fresh.fingerprint(), fresh.priorities())));
        assertTrue(ex.getMessage().contains("更新的排产版本"), ex.getMessage());
        assertEquals(1, scheduleRepository.count());

        // 失败方刷新后看到最新顺序
        ScheduleView current = scheduleService.current();
        assertEquals("v1", current.versionName());
        assertFalse(current.stale());
    }

    /** 数据变化 → 已保存版本标为过期；基于最新数据重算后可保存新版本 */
    @Test
    void dataChange_marksVersionStale_thenRecomputeAndSaveNewVersion() {
        ScheduleView pv = previewDefault();
        ScheduleView v1 = scheduleService.save(
                new ScheduleSaveRequest("v1", "张三", null, pv.fingerprint(), pv.priorities()));
        assertFalse(scheduleService.current().stale());

        // 烤箱状态变化 → 过期
        new TransactionTemplate(txManager).executeWithoutResult((TransactionStatus s) -> {
            jdbc.update("UPDATE oven SET status='故障' WHERE id=5");
            em.clear();
        });
        assertTrue(scheduleService.current().stale());

        // 交付截止时间变化 → 仍过期；重算（preview 基于最新数据）后可保存新版本
        new TransactionTemplate(txManager).executeWithoutResult((TransactionStatus s) -> {
            jdbc.update("UPDATE bake_order SET deadline=? WHERE id=3",
                    Timestamp.valueOf(LocalDateTime.now().plusHours(5)));
            em.clear();
        });
        ScheduleView recomputed = previewDefault();
        ScheduleView v2 = scheduleService.save(new ScheduleSaveRequest(
                "v2", "李四", v1.versionId(), recomputed.fingerprint(), recomputed.priorities()));
        assertFalse(v2.stale());
        assertEquals(2, scheduleRepository.count());
        assertEquals(v2.versionId(), metaRepository.findById(1L).orElseThrow().latestVersionId);
    }

    /** 两名操作员同时保存同一排产：只能保留一个版本，失败方得到明确提示 */
    @Test
    void concurrentSave_onlyOneVersionKept() throws Exception {
        ScheduleView pv = previewDefault();
        ScheduleSaveRequest reqA = new ScheduleSaveRequest("甲的排产", "甲", null, pv.fingerprint(), pv.priorities());
        ScheduleSaveRequest reqB = new ScheduleSaveRequest("乙的排产", "乙", null, pv.fingerprint(), pv.priorities());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> f1 = pool.submit(() -> {
            try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            scheduleService.save(reqA);
        });
        Future<?> f2 = pool.submit(() -> {
            try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            scheduleService.save(reqB);
        });
        start.countDown();

        int ok = 0, fail = 0;
        String failMsg = null;
        for (Future<?> f : List.of(f1, f2)) {
            try {
                f.get(30, TimeUnit.SECONDS);
                ok++;
            } catch (Exception e) {
                fail++;
                Throwable c = e.getCause() != null ? e.getCause() : e;
                failMsg = c.getMessage();
            }
        }
        pool.shutdown();

        assertEquals(1, ok, "恰好一方保存成功");
        assertEquals(1, fail, "恰好一方失败");
        assertNotNull(failMsg);
        assertTrue(failMsg.contains("更新的排产版本"), "失败方应看到版本冲突提示：" + failMsg);
        assertEquals(1, scheduleRepository.count(), "只保留一个版本");
        assertEquals(4, itemRepository.count());
    }

    /** 指纹确定性：数据不变时两次计算指纹一致 */
    @Test
    void fingerprint_deterministic() {
        assertEquals(previewDefault().fingerprint(), previewDefault().fingerprint());
    }
}
