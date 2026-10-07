package com.github.xingjianluo.springbootconfighotswapjava.tests;

import com.github.xingjianluo.springbootconfighotswapjava.config.ConfigLayer;
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
 * 分层账本测试：三来源独立维护、固定优先级、显式清空/回退、清空致非法整体回滚、
 * 内置默认只读，以及分层场景下的并发读一致性。
 * 每个用例日志均打印：输入、当时各来源内容、最终判定与原因。
 */
class LayeredLedgerTest {

    private static final Logger log = LoggerFactory.getLogger(LayeredLedgerTest.class);

    private static Map<ConfigLayer, Map<String, String>> layer(ConfigLayer l, Map<String, String> entries) {
        return Map.of(l, entries);
    }

    private static String layersOf(HotSwapRuntime runtime) {
        return runtime.current().layers().layers().toString();
    }

    @Test
    void alternatingUpdatesAcrossLayersKeepStablePriority() {
        HotSwapRuntime runtime = new HotSwapRuntime();
        log.info("用例=交替更新不同来源优先级稳定 初始各来源={}", layersOf(runtime));

        // 1) 运行时注入 prefix=High
        UpdateOutcome o1 = runtime.update(
                layer(ConfigLayer.RUNTIME_INJECTION, Map.of("greeting.prefix", "High")), Map.of());
        log.info("第1轮 输入=RUNTIME_INJECTION{{greeting.prefix=High}} outcome={} 当时各来源={}",
                o1, layersOf(runtime));
        assertTrue(o1.success());
        assertEquals("High", runtime.current().config().greetingPrefix());

        // 2) 只更新外部文件层：运行时层的保存内容不动，高优先值不被低优先更新覆盖
        UpdateOutcome o2 = runtime.update(
                layer(ConfigLayer.EXTERNAL_FILE, Map.of("greeting.prefix", "Mid", "max.connections", "50")), Map.of());
        RuntimeSnapshot s2 = runtime.current();
        log.info("第2轮 输入=EXTERNAL_FILE{{greeting.prefix=Mid,max.connections=50}} outcome={} 当时各来源={} "
                        + "判定=prefix仍取运行时层的High, maxConnections取外部文件的50",
                o2, layersOf(runtime));
        assertTrue(o2.success());
        assertEquals("High", s2.config().greetingPrefix(), "低优先层更新不得覆盖高优先层已保存的值");
        assertEquals(50, s2.config().maxConnections());
        assertEquals("Mid", s2.layers().entriesOf(ConfigLayer.EXTERNAL_FILE).get("greeting.prefix"),
                "外部文件层自己的账本内容要原样保存");
        assertEquals("High", s2.layers().entriesOf(ConfigLayer.RUNTIME_INJECTION).get("greeting.prefix"),
                "运行时注入层账本保持上一次保存的内容");
        assertEquals(ConfigLayer.RUNTIME_INJECTION, s2.provenance().get("greeting.prefix"));
        assertEquals(ConfigLayer.EXTERNAL_FILE, s2.provenance().get("max.connections"));

        // 3) 再次只更新外部文件层：运行时注入值依然稳定胜出
        UpdateOutcome o3 = runtime.update(
                layer(ConfigLayer.EXTERNAL_FILE, Map.of("greeting.prefix", "Mid2", "max.connections", "60")), Map.of());
        RuntimeSnapshot s3 = runtime.current();
        log.info("第3轮 输入=EXTERNAL_FILE{{greeting.prefix=Mid2,max.connections=60}} outcome={} 当时各来源={} "
                        + "判定=prefix仍为High, maxConnections=60", o3, layersOf(runtime));
        assertTrue(o3.success());
        assertEquals("High", s3.config().greetingPrefix());
        assertEquals(60, s3.config().maxConnections());
        assertEquals("Mid2", s3.layers().entriesOf(ConfigLayer.EXTERNAL_FILE).get("greeting.prefix"));

        // 4) 替换运行时注入层：该层内容整体换新，未提及的键不再由该层提供
        UpdateOutcome o4 = runtime.update(
                layer(ConfigLayer.RUNTIME_INJECTION, Map.of("greeting.prefix", "Higher")), Map.of());
        RuntimeSnapshot s4 = runtime.current();
        log.info("第4轮 输入=RUNTIME_INJECTION{{greeting.prefix=Higher}} outcome={} 当时各来源={} "
                        + "判定=prefix=Higher", o4, layersOf(runtime));
        assertTrue(o4.success());
        assertEquals("Higher", s4.config().greetingPrefix());
        assertEquals(ConfigLayer.RUNTIME_INJECTION, s4.provenance().get("greeting.prefix"));
    }

    @Test
    void clearingHighLayerRestoresLowerLayerValue() {
        HotSwapRuntime runtime = new HotSwapRuntime();
        runtime.update(layer(ConfigLayer.EXTERNAL_FILE, Map.of("greeting.prefix", "Mid")), Map.of());
        runtime.update(layer(ConfigLayer.RUNTIME_INJECTION, Map.of("greeting.prefix", "High")), Map.of());
        log.info("用例=清空高优先来源后低优先值恢复 前置各来源={} 生效prefix={}",
                layersOf(runtime), runtime.current().config().greetingPrefix());
        assertEquals("High", runtime.current().config().greetingPrefix());

        // 清空运行时注入层（空内容=清空，不是无变化）：立即回退到外部文件层的值
        UpdateOutcome clear1 = runtime.update(layer(ConfigLayer.RUNTIME_INJECTION, Map.of()), Map.of());
        RuntimeSnapshot s1 = runtime.current();
        log.info("清空第1步 输入=RUNTIME_INJECTION{{}} outcome={} 当时各来源={} 判定=prefix回退为外部文件的Mid",
                clear1, layersOf(runtime));
        assertTrue(clear1.success());
        assertTrue(s1.layers().entriesOf(ConfigLayer.RUNTIME_INJECTION).isEmpty(), "该层账本已被清空");
        assertEquals("Mid", s1.config().greetingPrefix());
        assertEquals(ConfigLayer.EXTERNAL_FILE, s1.provenance().get("greeting.prefix"));

        // 再清空外部文件层：回退到内置默认出厂基线
        UpdateOutcome clear2 = runtime.update(layer(ConfigLayer.EXTERNAL_FILE, Map.of()), Map.of());
        RuntimeSnapshot s2 = runtime.current();
        log.info("清空第2步 输入=EXTERNAL_FILE{{}} outcome={} 当时各来源={} 判定=prefix回退为内置默认Hello",
                clear2, layersOf(runtime));
        assertTrue(clear2.success());
        assertEquals("Hello", s2.config().greetingPrefix());
        assertEquals(ConfigLayer.BUILTIN_DEFAULT, s2.provenance().get("greeting.prefix"));
    }

    @Test
    void clearThatCausesMissingRequiredRollsBackWholly() {
        HotSwapRuntime runtime = new HotSwapRuntime();
        // 运行时层提供合法 prefix，掩盖外部文件层保存的空白 prefix
        runtime.update(layer(ConfigLayer.RUNTIME_INJECTION, Map.of("greeting.prefix", "Hi")), Map.of());
        UpdateOutcome masked = runtime.update(
                layer(ConfigLayer.EXTERNAL_FILE, Map.of("greeting.prefix", "")), Map.of());
        log.info("用例=清空导致必填缺失整体回滚 前置: 输入=EXTERNAL_FILE{{greeting.prefix=''}}(被运行时层掩盖) "
                + "outcome={} 当时各来源={}", masked, layersOf(runtime));
        assertTrue(masked.success(), "被高优先层掩盖时该轮合并结果合法，应生效");

        RuntimeSnapshot before = runtime.current();
        // 清空运行时层后，外部文件层的空白 prefix 暴露 -> 必填缺失 -> 整体不生效
        UpdateOutcome denied = runtime.update(layer(ConfigLayer.RUNTIME_INJECTION, Map.of()), Map.of());
        RuntimeSnapshot after = runtime.current();
        log.info("清空运行时层 输入=RUNTIME_INJECTION{{}} outcome={} 当时各来源={} "
                        + "判定=整体拒绝, 版本/账本/生效值保持原样, 原因={}",
                denied, layersOf(runtime), runtime.lastFailureReason().orElse(""));
        assertFalse(denied.success());
        assertEquals(before.version(), after.version(), "失败时版本不得前进");
        assertEquals(before.config(), after.config(), "失败时生效配置保持原样");
        assertEquals(before.layers().layers(), after.layers().layers(), "失败时各来源账本保持原样");
        String reason = runtime.lastFailureReason().orElse("");
        assertTrue(reason.contains("MISSING_REQUIRED"), "失败原因应为必填缺失: " + reason);
        assertFalse(reason.contains("EXTENSION_MISSING"), "失败原因必须可与扩展不可用区分: " + reason);
    }

    @Test
    void clearThatUnmasksIllegalValueRollsBackWholly() {
        HotSwapRuntime runtime = new HotSwapRuntime();
        // 运行时层提供合法 max.connections，掩盖外部文件层的非法值
        runtime.update(layer(ConfigLayer.RUNTIME_INJECTION, Map.of("max.connections", "50")), Map.of());
        UpdateOutcome masked = runtime.update(
                layer(ConfigLayer.EXTERNAL_FILE, Map.of("max.connections", "abc")), Map.of());
        log.info("用例=清空导致取值非法整体回滚 前置: 输入=EXTERNAL_FILE{{max.connections=abc}}(被掩盖) "
                + "outcome={} 当时各来源={}", masked, layersOf(runtime));
        assertTrue(masked.success());

        RuntimeSnapshot before = runtime.current();
        UpdateOutcome denied = runtime.update(layer(ConfigLayer.RUNTIME_INJECTION, Map.of()), Map.of());
        log.info("清空运行时层 输入=RUNTIME_INJECTION{{}} outcome={} 当时各来源={} 判定=整体拒绝, 原因={}",
                denied, layersOf(runtime), runtime.lastFailureReason().orElse(""));
        assertFalse(denied.success());
        assertEquals(before.version(), runtime.currentVersion());
        assertEquals(before.layers().layers(), runtime.current().layers().layers());
        String reason = runtime.lastFailureReason().orElse("");
        assertTrue(reason.contains("ILLEGAL_VALUE"), "失败原因应为取值非法: " + reason);
        assertFalse(reason.contains("EXTENSION_MISSING"), "失败原因必须可与扩展不可用区分: " + reason);
    }

    @Test
    void builtinDefaultLayerIsReadOnlyForRuntimeRequests() {
        HotSwapRuntime runtime = new HotSwapRuntime();
        RuntimeSnapshot before = runtime.current();
        log.info("用例=内置默认只读 初始各来源={}", layersOf(runtime));

        UpdateOutcome denied = runtime.update(
                layer(ConfigLayer.BUILTIN_DEFAULT, Map.of("greeting.prefix", "Hacked")), Map.of());
        log.info("输入=BUILTIN_DEFAULT{{greeting.prefix=Hacked}} outcome={} 当时各来源={} "
                        + "判定=明确拒绝, 版本与出厂基线不变, 原因={}",
                denied, layersOf(runtime), runtime.lastFailureReason().orElse(""));
        assertFalse(denied.success());
        assertEquals(before.version(), runtime.currentVersion(), "拒绝改写时版本不得前进");
        assertEquals(before.config(), runtime.current().config());
        assertEquals("Hello", runtime.current().layers()
                .entriesOf(ConfigLayer.BUILTIN_DEFAULT).get("greeting.prefix"), "出厂基线不得被改写");
        String reason = runtime.lastFailureReason().orElse("");
        assertTrue(reason.contains("BUILTIN_LAYER_READONLY"), "拒绝原因应明确为内置层只读: " + reason);
        assertFalse(reason.contains("EXTENSION_MISSING"), "原因须可与扩展不可用区分: " + reason);
        assertFalse(reason.contains("ILLEGAL_VALUE"), "原因须可与取值非法区分: " + reason);
    }

    @Test
    void concurrentReadersSeeConsistentLayeredState() throws Exception {
        HotSwapRuntime runtime = new HotSwapRuntime();
        int rounds = 150;
        int readers = 8;
        AtomicInteger inconsistencies = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        // 写线程：每轮交替更新两个不同来源层（外部文件给 maxConnections=n，运行时注入给 prefix=P<n>）
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        Future<?> writer = pool.submit(() -> {
            try {
                start.await();
                for (int n = 1; n <= rounds; n++) {
                    runtime.update(layer(ConfigLayer.EXTERNAL_FILE,
                            Map.of("max.connections", String.valueOf(n))), Map.of());
                    runtime.update(layer(ConfigLayer.RUNTIME_INJECTION,
                            Map.of("greeting.prefix", "P" + n)), Map.of());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 读线程：校验任一快照内 生效值==该快照三层账本按固定优先级合并的结果，且来源说明对得上
        List<Future<?>> readerFutures = new ArrayList<>();
        for (int r = 0; r < readers; r++) {
            readerFutures.add(pool.submit(() -> {
                try {
                    start.await();
                    long lastVersion = 0;
                    while (!writer.isDone()) {
                        RuntimeSnapshot snap = runtime.current();
                        Map<String, String> rt = snap.layers().entriesOf(ConfigLayer.RUNTIME_INJECTION);
                        Map<String, String> ex = snap.layers().entriesOf(ConfigLayer.EXTERNAL_FILE);
                        Map<String, String> bi = snap.layers().entriesOf(ConfigLayer.BUILTIN_DEFAULT);

                        String expectedPrefix = rt.getOrDefault("greeting.prefix",
                                ex.getOrDefault("greeting.prefix", bi.get("greeting.prefix")));
                        ConfigLayer expectedPrefixLayer = rt.containsKey("greeting.prefix")
                                ? ConfigLayer.RUNTIME_INJECTION
                                : ex.containsKey("greeting.prefix")
                                        ? ConfigLayer.EXTERNAL_FILE : ConfigLayer.BUILTIN_DEFAULT;
                        String maxRaw = rt.getOrDefault("max.connections",
                                ex.getOrDefault("max.connections", null));
                        int expectedMax = maxRaw != null ? Integer.parseInt(maxRaw) : 100;

                        boolean consistent = snap.config().greetingPrefix().equals(expectedPrefix)
                                && snap.config().maxConnections() == expectedMax
                                && snap.provenance().get("greeting.prefix") == expectedPrefixLayer;
                        if (!consistent) {
                            inconsistencies.incrementAndGet();
                            log.error("读到对不上的中间状态: version={} config={} 各来源={} provenance={}",
                                    snap.version(), snap.config(), snap.layers().layers(), snap.provenance());
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
        log.info("用例=分层并发读一致性 输入=1写线程x{}轮交替更新EXTERNAL_FILE/RUNTIME_INJECTION, {}读线程 "
                + "判定依据=任一快照内 生效值==三层账本按优先级合并结果 且 provenance 与账本一致 且版本单调", rounds, readers);
        start.countDown();
        writer.get(30, TimeUnit.SECONDS);
        for (Future<?> f : readerFutures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        log.info("输出: 最终版本={} 最终各来源={} 不一致次数={}",
                runtime.currentVersion(), layersOf(runtime), inconsistencies.get());
        assertEquals(0, inconsistencies.get(), "并发读绝不能看到层与生效值/来源说明对不上的中间状态");
        assertEquals(1L + rounds * 2L, runtime.currentVersion(), "每轮两次合法更新都必须恰好生效一次");
    }
}
