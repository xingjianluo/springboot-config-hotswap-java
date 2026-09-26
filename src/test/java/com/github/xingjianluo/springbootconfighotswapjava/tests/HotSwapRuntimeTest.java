package com.github.xingjianluo.springbootconfighotswapjava.tests;

import com.github.xingjianluo.springbootconfighotswapjava.config.ConfigLayer;
import com.github.xingjianluo.springbootconfighotswapjava.ext.FormalGreetingService;
import com.github.xingjianluo.springbootconfighotswapjava.runtime.HotSwapRuntime;
import com.github.xingjianluo.springbootconfighotswapjava.runtime.RuntimeSnapshot;
import com.github.xingjianluo.springbootconfighotswapjava.runtime.UpdateOutcome;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 热更新运行时测试：并发读一致性、更新串行化、失败回滚与可区分失败原因。
 */
class HotSwapRuntimeTest {

    private static final Logger log = LoggerFactory.getLogger(HotSwapRuntimeTest.class);

    private static Map<ConfigLayer, Map<String, String>> runtimeLayer(Map<String, String> entries) {
        return Map.of(ConfigLayer.RUNTIME_INJECTION, entries);
    }

    @Test
    void failedUpdateRollsBackAndKeepsDistinguishableReason() {
        HotSwapRuntime runtime = new HotSwapRuntime();
        long v0 = runtime.currentVersion();

        // 先做一次合法更新
        UpdateOutcome ok = runtime.update(runtimeLayer(Map.of("greeting.prefix", "Hi")), Map.of());
        log.info("前置合法更新: outcome={}", ok);
        assertTrue(ok.success());
        assertEquals(v0 + 1, runtime.currentVersion());
        RuntimeSnapshot good = runtime.current();

        // 失败场景一：取值非法 -> 回退，原因含 ILLEGAL_VALUE
        UpdateOutcome bad1 = runtime.update(runtimeLayer(Map.of("max.connections", "-5")), Map.of());
        log.info("失败场景一(取值非法): 输入=max.connections=-5 outcome={} 判定依据=版本不变且原因含ILLEGAL_VALUE", bad1);
        assertFalse(bad1.success());
        assertEquals(good.version(), runtime.currentVersion(), "失败后必须保持旧版本");
        assertEquals(good.config(), runtime.current().config(), "失败后配置必须回退为旧值");
        assertTrue(runtime.lastFailureReason().orElse("").contains("ILLEGAL_VALUE"));

        // 失败场景二：引用未注册扩展 -> 回退，原因含 EXTENSION_MISSING，可与场景一区分
        UpdateOutcome bad2 = runtime.update(runtimeLayer(Map.of("greeting.impl", "not-exist")), Map.of());
        log.info("失败场景二(扩展缺失): 输入=greeting.impl=not-exist outcome={} 判定依据=原因含EXTENSION_MISSING且与ILLEGAL_VALUE可区分", bad2);
        assertFalse(bad2.success());
        assertEquals(good.version(), runtime.currentVersion());
        String reason2 = runtime.lastFailureReason().orElse("");
        assertTrue(reason2.contains("EXTENSION_MISSING"));
        assertFalse(reason2.contains("ILLEGAL_VALUE"), "两次失败原因必须可区分");
    }

    @Test
    void concurrentReadersNeverSeeHalfAppliedState() throws Exception {
        HotSwapRuntime runtime = new HotSwapRuntime();
        int rounds = 200;
        int readers = 8;
        AtomicInteger inconsistencies = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        // 写线程：每轮把 prefix=P<n> 与 maxConnections=<n> 作为一次原子更新写入
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        Future<?> writer = pool.submit(() -> {
            try {
                start.await();
                for (int n = 1; n <= rounds; n++) {
                    runtime.update(runtimeLayer(Map.of(
                            "greeting.prefix", "P" + n,
                            "max.connections", String.valueOf(n))), Map.of());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 读线程：持续读取快照，校验 prefix 后缀与 maxConnections 永远一致（无半生效状态）
        List<Future<?>> readerFutures = new ArrayList<>();
        for (int r = 0; r < readers; r++) {
            readerFutures.add(pool.submit(() -> {
                try {
                    start.await();
                    long lastVersion = 0;
                    while (!writer.isDone()) {
                        RuntimeSnapshot snap = runtime.current();
                        String prefix = snap.config().greetingPrefix();
                        int maxConn = snap.config().maxConnections();
                        boolean consistent = !prefix.startsWith("P")
                                || Integer.parseInt(prefix.substring(1)) == maxConn;
                        if (!consistent) {
                            inconsistencies.incrementAndGet();
                            log.error("读到中间状态: version={} prefix={} maxConnections={}",
                                    snap.version(), prefix, maxConn);
                        }
                        if (snap.version() < lastVersion) {
                            inconsistencies.incrementAndGet();
                            log.error("版本回退: {} -> {}", lastVersion, snap.version());
                        }
                        lastVersion = snap.version();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        log.info("用例=并发读一致性 输入=1写线程x{}轮原子更新(prefix=P<n>+maxConnections=<n>), {}读线程 判定依据=任何快照内 prefix后缀==maxConnections 且版本单调",
                rounds, readers);
        start.countDown();
        writer.get(30, TimeUnit.SECONDS);
        for (Future<?> f : readerFutures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        log.info("输出: 最终版本={} 不一致次数={}", runtime.currentVersion(), inconsistencies.get());
        assertEquals(0, inconsistencies.get(), "并发读绝不能看到只生效一半的中间状态");
        assertEquals(1L + rounds, runtime.currentVersion(), "每轮合法更新必须恰好生效一次");
    }

    @Test
    void concurrentDuplicateUpdatesAreSerialized() throws Exception {
        HotSwapRuntime runtime = new HotSwapRuntime();
        long v0 = runtime.currentVersion();
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<UpdateOutcome>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                // 所有线程提交完全相同的重复更新
                return runtime.update(runtimeLayer(Map.of("greeting.prefix", "Same")), Map.of());
            }));
        }
        log.info("用例=重复更新串行化 输入={}个线程并发提交相同更新 判定依据=全部成功、版本恰好+{}、最终配置一致", threads, threads);
        start.countDown();

        int successes = 0;
        for (Future<UpdateOutcome> f : futures) {
            UpdateOutcome o = f.get(30, TimeUnit.SECONDS);
            if (o.success()) {
                successes++;
            }
        }
        pool.shutdownNow();

        log.info("输出: 成功数={} 最终版本={} 最终配置={}", successes, runtime.currentVersion(), runtime.current().config());
        assertEquals(threads, successes, "串行化后重复更新应逐个生效，不得交错失败");
        assertEquals(v0 + threads, runtime.currentVersion(), "版本号必须严格递增，无交错");
        assertEquals("Same", runtime.current().config().greetingPrefix());
    }

    @Test
    void extensionReplacementTakesEffectAtomically() {
        HotSwapRuntime runtime = new HotSwapRuntime();
        // 注册新扩展并切换引用，一次更新内原子生效
        UpdateOutcome outcome = runtime.update(
                runtimeLayer(Map.of("greeting.impl", FormalGreetingService.ID)), Map.of());
        log.info("用例=扩展原子切换 输入=greeting.impl=formal outcome={} 判定依据=同一快照内 config.greetingImpl 与 activeGreeting.id 一致", outcome);

        assertTrue(outcome.success());
        RuntimeSnapshot snap = runtime.current();
        log.info("输出: version={} greetingImpl={} activeGreeting.id={} greet={}",
                snap.version(), snap.config().greetingImpl(), snap.activeGreeting().id(),
                snap.activeGreeting().greet("Vincent"));
        assertEquals(FormalGreetingService.ID, snap.config().greetingImpl());
        assertEquals(snap.config().greetingImpl(), snap.activeGreeting().id());
        assertTrue(snap.activeGreeting().greet("Vincent").startsWith("Dear"));
    }
}
