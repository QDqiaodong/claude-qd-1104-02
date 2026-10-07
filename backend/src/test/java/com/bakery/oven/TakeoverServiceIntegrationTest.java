package com.bakery.oven;

import com.bakery.oven.common.BizException;
import com.bakery.oven.dto.TakeoverItem;
import com.bakery.oven.dto.TakeoverPreview;
import com.bakery.oven.dto.TakeoverRequest;
import com.bakery.oven.dto.TakeoverResult;
import com.bakery.oven.entity.BakeOrder;
import com.bakery.oven.entity.Oven;
import com.bakery.oven.repository.BakeOrderRepository;
import com.bakery.oven.repository.OvenRepository;
import com.bakery.oven.service.BakeOrderService;
import com.bakery.oven.service.TakeoverService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TakeoverServiceIntegrationTest {

    @Autowired TakeoverService takeoverService;
    @Autowired BakeOrderService bakeOrderService;
    @Autowired OvenRepository ovenRepository;
    @Autowired BakeOrderRepository orderRepository;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    private Oven oven(long id) {
        return ovenRepository.findById(id).orElseThrow();
    }

    private BakeOrder order(long id) {
        return orderRepository.findById(id).orElseThrow();
    }

    private TakeoverRequest req(TakeoverItem... items) {
        return new TakeoverRequest(List.of(items));
    }

    /** 每个用例前恢复种子数据（H2 MySQL 模式） */
    @BeforeEach
    void resetData() {
        new TransactionTemplate(txManager).executeWithoutResult((TransactionStatus s) -> {
            em.clear();
            jdbc.execute("SET REFERENTIAL_INTEGRITY = FALSE");
            jdbc.execute("DELETE FROM shipment");
            jdbc.execute("DELETE FROM bake_order");
            jdbc.execute("DELETE FROM bread_recipe");
            jdbc.execute("DELETE FROM oven");
            jdbc.execute("ALTER TABLE oven ALTER COLUMN id RESTART WITH 1");
            jdbc.execute("ALTER TABLE bread_recipe ALTER COLUMN id RESTART WITH 1");
            jdbc.execute("ALTER TABLE bake_order ALTER COLUMN id RESTART WITH 1");
            jdbc.batchUpdate(
                "INSERT INTO oven (id, code, capacity, status, created_at) VALUES (1,'OV-01',4,'空闲',NULL),"
                    + "(2,'OV-02',6,'占用',NULL),(3,'OV-03',3,'故障',NULL),"
                    + "(4,'OV-04',5,'空闲',NULL),(5,'OV-05',2,'空闲',NULL)");
            jdbc.batchUpdate(
                "INSERT INTO bread_recipe (id, name, need_trays, bake_minutes, status, created_at) VALUES "
                    + "(1,'法棍',2,30,'上架',NULL),(2,'牛角包',3,25,'上架',NULL),(3,'乡村面包',4,45,'上架',NULL)");
            // 故障炉 OV-03：1 张待烤(2盘) + 1 张烘焙中(3盘)；OV-02 上另有 1 张烘焙中
            jdbc.batchUpdate(
                "INSERT INTO bake_order (id, oven_id, recipe_id, trays, start_time, status, created_at) VALUES "
                    + "(1,3,1,2,NULL,'待烤',NULL),"
                    + "(2,3,2,3,'2026-10-06 09:00:00','烘焙中',NULL),"
                    + "(3,2,1,2,'2026-10-06 08:00:00','烘焙中',NULL)");
            jdbc.execute("SET REFERENTIAL_INTEGRITY = TRUE");
            em.clear();
        });
    }

    /** 正常批量接管：待烤迁移仍待烤；烘焙中迁移保留状态与原开始时间；目标炉置占用 */
    @Test
    void batchTakeover_ok_statusAndStartTimePreserved() {
        var startBefore = order(2).startTime;
        // 工单1(待烤,2盘) → OV-01(容量4,空闲)；工单2(烘焙中,3盘) → OV-04(容量5,空闲)
        TakeoverResult result = takeoverService.takeover(3L, req(
                new TakeoverItem(1L, 1L),
                new TakeoverItem(2L, 4L)));

        assertEquals(2, result.movedCount());
        assertEquals(1L, order(1).ovenId);
        assertEquals("待烤", order(1).status);
        assertNull(order(1).startTime);
        assertEquals(4L, order(2).ovenId);
        assertEquals("烘焙中", order(2).status);
        assertEquals(startBefore, order(2).startTime);
        // 收到烘焙中工单 → 占用；只收待烤 → 空闲；源炉仍故障
        assertEquals("占用", oven(4L).status);
        assertEquals("空闲", oven(1L).status);
        assertEquals("故障", oven(3L).status);
    }

    /** 待烤工单迁到空闲炉：目标炉保持空闲，后续可正常开始烘焙 */
    @Test
    void pendingToIdleTarget_staysIdle_thenCanStart() {
        takeoverService.takeover(3L, req(new TakeoverItem(1L, 1L)));
        assertEquals("空闲", oven(1L).status);
        assertEquals("待烤", order(1).status);
    }

    /** 任一张不满足条件 → 整批回滚：归属、源炉状态、目标炉占用全部原样 */
    @Test
    void batchTakeover_oneInvalid_allRolledBackWithSpecificReasons() {
        // 工单2(烘焙中,3盘) → OV-05(容量2,空闲)：容量不足；工单1 合法
        BizException ex = assertThrows(BizException.class, () ->
                takeoverService.takeover(3L, req(
                        new TakeoverItem(1L, 1L),
                        new TakeoverItem(2L, 5L))));

        String msg = ex.getMessage();
        assertTrue(msg.contains("整批未迁移"), msg);
        assertTrue(msg.contains("#2"), msg);
        assertTrue(msg.contains("容量不足"), msg);
        assertTrue(msg.contains("OV-05"), msg);

        // 整批不落库：工单1 也没有被迁走
        assertEquals(3L, order(1).ovenId);
        assertEquals(3L, order(2).ovenId);
        assertEquals("故障", oven(3L).status);
        assertEquals("空闲", oven(1L).status);
        assertEquals("空闲", oven(5L).status);
    }

    /** 目标炉已有烘焙中工单 → 不允许再接收第二张 */
    @Test
    void targetWithBakingOrder_rejectedForBaking() {
        BizException ex = assertThrows(BizException.class, () ->
                takeoverService.takeover(3L, req(new TakeoverItem(2L, 2L))));
        assertTrue(ex.getMessage().contains("不能再接收第二张烘焙中工单"), ex.getMessage());
        assertEquals(3L, order(2).ovenId);
    }

    /** 目标炉非空闲（占用且无烘焙工单的异常态也算）→ 拒绝 */
    @Test
    void nonIdleTarget_rejected() {
        BizException ex = assertThrows(BizException.class, () ->
                takeoverService.takeover(3L, req(new TakeoverItem(1L, 2L))));
        assertTrue(ex.getMessage().contains("必须处于空闲"), ex.getMessage());
        assertEquals(3L, order(1).ovenId);
    }

    /** 本批内同一工单重复出现 → 整批拒绝 */
    @Test
    void duplicateOrderWithinBatch_rejected() {
        BizException ex = assertThrows(BizException.class, () ->
                takeoverService.takeover(3L, req(
                        new TakeoverItem(1L, 1L),
                        new TakeoverItem(1L, 4L))));
        assertTrue(ex.getMessage().contains("同一工单在本批接管中出现多次"), ex.getMessage());
        assertEquals(3L, order(1).ovenId);
    }

    /** 非故障炉不能发起接管 */
    @Test
    void nonBrokenSource_rejected() {
        BizException ex = assertThrows(BizException.class, () ->
                takeoverService.takeover(1L, req(new TakeoverItem(1L, 4L))));
        assertTrue(ex.getMessage().contains("只有故障烤箱"), ex.getMessage());
    }

    /** 工单不属于源故障炉 → 定位到具体工单 */
    @Test
    void orderNotBelongToSource_rejected() {
        BizException ex = assertThrows(BizException.class, () ->
                takeoverService.takeover(3L, req(new TakeoverItem(3L, 4L))));
        assertTrue(ex.getMessage().contains("不属于故障炉 OV-03"), ex.getMessage());
    }

    /** 预览：候选炉资格、原因与风险等级齐全 */
    @Test
    void preview_exposesOrdersTargetsAndRisk() {
        TakeoverPreview pv = takeoverService.preview(3L);
        assertEquals(2, pv.orders().size());
        assertEquals("medium", pv.orders().get(0).riskLevel());
        assertEquals("high", pv.orders().get(1).riskLevel());
        var ov02 = pv.targetOvens().stream().filter(t -> t.id() == 2L).findFirst().orElseThrow();
        assertFalse(ov02.eligible());
        assertTrue(ov02.reason().contains("烘焙中工单"), ov02.reason());
        var ov05 = pv.targetOvens().stream().filter(t -> t.id() == 5L).findFirst().orElseThrow();
        assertTrue(ov05.eligible());
    }

    /** 修复为空闲后，未迁移工单仍保留；再次故障可继续接管 */
    @Test
    void repairKeepsOrders_andCanTakeoverAgainWhenReBroken() {
        Oven broken = oven(3L);
        broken.status = "空闲";
        ovenRepository.save(broken);
        assertEquals(3L, order(1).ovenId);
        assertEquals(3L, order(2).ovenId);
        assertThrows(BizException.class, () -> takeoverService.preview(3L));

        broken.status = "故障";
        ovenRepository.save(broken);
        TakeoverPreview preview = takeoverService.preview(3L);
        assertEquals(2, preview.orders().size());
    }

    /**
     * 并发：两批接管同时选中同一空闲目标炉迁移烘焙中工单，只能成功一批；
     * 失败批整体回滚，不留半迁移数据，工单归属一致。
     */
    @Test
    void concurrentTakeoverSameTarget_onlyOneSucceeds() throws Exception {
        newOvenWithBakingOrder(6L, "OV-06", 10L);
        newOvenWithBakingOrder(7L, "OV-07", 11L);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<?> f1 = pool.submit(() -> {
            try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            takeoverService.takeover(6L, req(new TakeoverItem(10L, 4L)));
        });
        Future<?> f2 = pool.submit(() -> {
            try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            takeoverService.takeover(7L, req(new TakeoverItem(11L, 4L)));
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

        assertEquals(1, ok, "恰好一批成功");
        assertEquals(1, fail, "恰好一批失败");
        assertNotNull(failMsg);
        assertTrue(failMsg.contains("烘焙中工单") || failMsg.contains("空闲"),
                "失败原因应定位到烘焙槽位/炉状态：" + failMsg);

        // 只有一张工单迁到 OV-04，另一张留在原故障炉；OV-04 上烘焙中工单唯一
        assertEquals(1, orderRepository.findByOvenIdAndStatus(4L, "烘焙中").size());
        int stillHome = (order(10).ovenId == 6L ? 1 : 0) + (order(11).ovenId == 7L ? 1 : 0);
        assertEquals(1, stillHome, "失败批的工单必须保留在原故障炉");
        assertEquals("故障", oven(6L).status);
        assertEquals("故障", oven(7L).status);
        assertEquals("占用", oven(4L).status);
    }

    /**
     * 并发开始：同一空闲炉上两张待烤工单同时 start，只能成功一张（验证炉行锁 + 不死锁）。
     */
    @Test
    void concurrentStartSameOven_onlyOneBaking() throws Exception {
        // OV-01(空闲,容量4) 上再造一张待烤工单，与工单1（目前在故障炉3）无关——先把工单1挪走
        // 直接在 OV-01 放两张待烤
        jdbc.update("UPDATE bake_order SET oven_id=1, status='待烤', start_time=NULL WHERE id=1");
        jdbc.update("INSERT INTO bake_order (id, oven_id, recipe_id, trays, start_time, status, created_at) "
                + "VALUES (20,1,1,2,NULL,'待烤',NULL)");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> f1 = pool.submit(() -> {
            try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            bakeOrderService.start(1L);
        });
        Future<?> f2 = pool.submit(() -> {
            try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            bakeOrderService.start(20L);
        });
        start.countDown();

        int ok = 0;
        for (Future<?> f : List.of(f1, f2)) {
            try { f.get(30, TimeUnit.SECONDS); ok++; } catch (Exception ignored) { }
        }
        pool.shutdown();

        assertEquals(1, ok, "同炉同时只能有一张烘焙中");
        assertEquals(1, orderRepository.findByOvenIdAndStatus(1L, "烘焙中").size());
        assertEquals("占用", oven(1L).status);
    }

    /** 在独立事务里准备：故障炉 + 一张烘焙中工单（带开始时间）。用原生 SQL 固定 id */
    private void newOvenWithBakingOrder(long ovenId, String code, long orderId) {
        new TransactionTemplate(txManager).executeWithoutResult((TransactionStatus s) -> {
            jdbc.update("INSERT INTO oven (id, code, capacity, status, created_at) VALUES (?,?,?,'故障',NULL)",
                    ovenId, code, 6);
            jdbc.update("INSERT INTO bake_order (id, oven_id, recipe_id, trays, start_time, status, created_at) "
                    + "VALUES (?,?,?,3,'2026-10-06 10:00:00','烘焙中',NULL)", orderId, ovenId, 2L);
            em.clear();
        });
    }
}
