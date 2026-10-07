package com.github.xingjianluo.springbootconfighotswapjava.tests;

import com.github.xingjianluo.springbootconfighotswapjava.config.ConfigLayer;
import com.github.xingjianluo.springbootconfighotswapjava.runtime.HotSwapRuntime;
import com.github.xingjianluo.springbootconfighotswapjava.runtime.RuntimeSnapshot;
import com.github.xingjianluo.springbootconfighotswapjava.runtime.UpdateOutcome;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
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
 * 分层账本语义测试：各来源独立保存、固定优先级、显式清空、内置层只读、
 * 失败整体回滚与并发读一致性。每个用例打印每次输入、当时各来源内容、最终判定和原因。
 */
class LayeredStateTest {

    private static final Logger log = LoggerFactory.getLogger(LayeredStateTest.class);

    private static Map<ConfigLayer, Map<String, String>> submit(ConfigLayer layer, Map<String, String> entries) {
        Map<ConfigLayer, Map<String, String>> m = new EnumMap<>(ConfigLayer.class);
        m.put(layer, entries);
        return m;
    }

    private static void logState(String tag, HotSwapRuntime runtime) {
        RuntimeSnapshot s = runtime.current();
        log.info("{} 当前各来源内容: layers={} provenance={} 生效配置={} version={}",
                tag, s.layers(), s.provenance(), s.config(), s.version());
    }

    @Test
    void alternatingUpdatesAcrossLayersKeepPriorityStable() {
        HotSwapRuntime runtime = new HotSwapRuntime();
        logState("初始", runtime);

        // 第1轮：外部文件层提交 max.connections=200 与 prefix=File
        Map<ConfigLayer, Map<String, String>> in1 = submit(ConfigLayer.EXTERNAL_FILE,
                Map.of("max.connections", "200", "greeting.prefix", "File"));
        UpdateOutcome o1 = runtime.update(in1, Map.of());
        logState("第1轮输入=" + in1 + " outcome=" + o1, runtime);
        assertTrue(o1.success());

        // 第2轮：运行时注入层提交 max.connections=300（高优先）
        Map<ConfigLayer, Map<String, String>> in2 = submit(ConfigLayer.RUNTIME_INJECTION,
                Map.of("max.connections", "300"));
        UpdateOutcome o2 = runtime.update(in2, Map.of());
        logState("第2轮输入=" + in2 + " outcome=" + o2, runtime);
        assertTrue(o2.success());
        assertEquals(300, runtime.current().config().maxConnections());

        // 第3轮：再次更新低优先的外部文件层（替换其账本），高优先注入值不得被覆盖
        Map<ConfigLayer, Map<String, String>> in3 = submit(ConfigLayer.EXTERNAL_FILE,
                Map.of("max.connections", "250"));
        UpdateOutcome o3 = runtime.update(in3, Map.of());
        logState("第3轮输入=" + in3 + " outcome=" + o3, runtime);
        assertTrue(o3.success());

        RuntimeSnapshot snap = runtime.current();
        log.info("最终判定: maxConnections={} 判定依据=RUNTIME_INJECTION(300) 高于后更新的 EXTERNAL_FILE(250)，"
                + "高优先已注入值不得被低优先来源的后续更新覆盖", snap.config().maxConnections());
        assertEquals(300, snap.config().maxConnections(),
                "先前注入的高优先值不得因后来更新低优先来源而被覆盖");
        // prefix 只在外部文件层第1轮保存过，第3轮替换该层账本后被移除 -> 回退内置默认
        assertEquals("Hello", snap.config().greetingPrefix());
        // 三本账本各自独立：内置层保持出厂内容，两个可写层各保存最近一次提交
        assertEquals(Map.of("greeting.prefix", "Hello", "greeting.impl", "default"),
                snap.layerEntries(ConfigLayer.BUILTIN_DEFAULT));
        assertEquals(Map.of("max.connections", "250"), snap.layerEntries(ConfigLayer.EXTERNAL_FILE));
        assertEquals(Map.of("max.connections", "300"), snap.layerEntries(ConfigLayer.RUNTIME_INJECTION));
        // 来源说明与生效值对得上
        assertEquals(ConfigLayer.RUNTIME_INJECTION, snap.winningLayerOf("max.connections"));
        assertEquals(ConfigLayer.BUILTIN_DEFAULT, snap.winningLayerOf("greeting.prefix"));
    }

    @Test
    void clearingHighLayerRestoresLowerLayerValue() {
        HotSwapRuntime runtime = new HotSwapRuntime();

        Map<ConfigLayer, Map<String, String>> in1 = submit(ConfigLayer.EXTERNAL_FILE,
                Map.of("greeting.prefix", "File", "max.connections", "200"));
        UpdateOutcome o1 = runtime.update(in1, Map.of());
        logState("第1轮输入=" + in1 + " outcome=" + o1, runtime);

        Map<ConfigLayer, Map<String, String>> in2 = submit(ConfigLayer.RUNTIME_INJECTION,
                Map.of("greeting.prefix", "Injected"));
        UpdateOutcome o2 = runtime.update(in2, Map.of());
        logState("第2轮输入=" + in2 + " outcome=" + o2, runtime);
        assertEquals("Injected", runtime.current().config().greetingPrefix());
        assertEquals(ConfigLayer.RUNTIME_INJECTION, runtime.current().winningLayerOf("greeting.prefix"));

        // 清空运行时注入层（空内容=清空，而非无变化）：prefix 应立即回退到外部文件层的取值
        Map<ConfigLayer, Map<String, String>> in3 = submit(ConfigLayer.RUNTIME_INJECTION, Map.of());
        UpdateOutcome o3 = runtime.update(in3, Map.of());
        logState("第3轮输入=" + in3 + "(清空RUNTIME_INJECTION) outcome=" + o3, runtime);
        log.info("最终判定: prefix={} 判定依据=清空高优先层后回退到 EXTERNAL_FILE 保存的 File",
                runtime.current().config().greetingPrefix());
        assertTrue(o3.success());
        assertEquals("File", runtime.current().config().greetingPrefix());
        assertEquals(ConfigLayer.EXTERNAL_FILE, runtime.current().winningLayerOf("greeting.prefix"));
        assertTrue(runtime.current().layerEntries(ConfigLayer.RUNTIME_INJECTION).isEmpty(),
                "被清空的层账本必须为空");
        // 外部文件层账本不受清空影响
        assertEquals(Map.of("greeting.prefix", "File", "max.connections", "200"),
                runtime.current().layerEntries(ConfigLayer.EXTERNAL_FILE));

        // 再清空外部文件层：回退到内置默认
        Map<ConfigLayer, Map<String, String>> in4 = submit(ConfigLayer.EXTERNAL_FILE, Map.of());
        UpdateOutcome o4 = runtime.update(in4, Map.of());
        logState("第4轮输入=" + in4 + "(清空EXTERNAL_FILE) outcome=" + o4, runtime);
        log.info("最终判定: prefix={} maxConnections={} 判定依据=两层均清空后回退内置默认 Hello/100",
                runtime.current().config().greetingPrefix(), runtime.current().config().maxConnections());
        assertTrue(o4.success());
        assertEquals("Hello", runtime.current().config().greetingPrefix());
        assertEquals(100, runtime.current().config().maxConnections());
        assertEquals(ConfigLayer.BUILTIN_DEFAULT, runtime.current().winningLayerOf("greeting.prefix"));
        assertEquals(ConfigLayer.BUILTIN_DEFAULT, runtime.current().winningLayerOf("max.connections"));
    }

    @Test
    void clearingCausingInvalidConfigRollsBackEntirely() {
        // 出厂账本：内置层不含必填的 greeting.prefix，仅外部文件层提供 -> 清空外部文件层将必填缺失
        Map<ConfigLayer, Map<String, String>> boot = new EnumMap<>(ConfigLayer.class);
        boot.put(ConfigLayer.BUILTIN_DEFAULT, Map.of("greeting.impl", "default"));
        boot.put(ConfigLayer.EXTERNAL_FILE, Map.of("greeting.prefix", "File"));
        HotSwapRuntime runtime = new HotSwapRuntime(boot);
        logState("初始(自定义出厂账本)", runtime);
        long v0 = runtime.currentVersion();

        // 清空外部文件层 -> greeting.prefix 必填缺失 -> 整体不生效
        Map<ConfigLayer, Map<String, String>> clearReq = submit(ConfigLayer.EXTERNAL_FILE, Map.of());
        UpdateOutcome cleared = runtime.update(clearReq, Map.of());
        logState("清空输入=" + clearReq + " outcome=" + cleared, runtime);
        log.info("最终判定: success={} 原因={} 判定依据=清空导致必填缺失必须整体回滚，原因码 MISSING_REQUIRED",
                cleared.success(), runtime.lastFailureReason().orElse(""));
        assertFalse(cleared.success());
        assertEquals(v0, runtime.currentVersion(), "失败版本不得前进");
        assertEquals(Map.of("greeting.prefix", "File"),
                runtime.current().layerEntries(ConfigLayer.EXTERNAL_FILE), "失败的清空不得改动账本");
        assertEquals("File", runtime.current().config().greetingPrefix());
        String clearReason = runtime.lastFailureReason().orElse("");
        assertTrue(clearReason.contains("MISSING_REQUIRED"));

        // 对照：扩展缺失的失败原因必须可与清空导致的缺失区分
        Map<ConfigLayer, Map<String, String>> extReq = submit(ConfigLayer.RUNTIME_INJECTION,
                Map.of("greeting.impl", "not-exist"));
        UpdateOutcome extFail = runtime.update(extReq, Map.of());
        logState("对照输入=" + extReq + " outcome=" + extFail, runtime);
        String extReason = runtime.lastFailureReason().orElse("");
        log.info("对照判定: 清空原因={} 扩展原因={} 判定依据=两者原因码不同可区分", clearReason, extReason);
        assertFalse(extFail.success());
        assertTrue(extReason.contains("EXTENSION_MISSING"));
        assertFalse(extReason.contains("MISSING_REQUIRED"));
        assertFalse(clearReason.contains("EXTENSION_MISSING"));
        assertEquals(v0, runtime.currentVersion(), "两次失败都不得改变版本与状态");
    }

    @Test
    void builtinDefaultLayerIsReadOnlyForRuntimeRequests() {
        HotSwapRuntime runtime = new HotSwapRuntime();
        logState("初始", runtime);
        long v0 = runtime.currentVersion();
        RuntimeSnapshot before = runtime.current();

        // 尝试改写内置默认层 -> 明确拒绝
        Map<ConfigLayer, Map<String, String>> writeReq = submit(ConfigLayer.BUILTIN_DEFAULT,
                Map.of("greeting.prefix", "Hacked"));
        UpdateOutcome write = runtime.update(writeReq, Map.of());
        logState("改写内置层输入=" + writeReq + " outcome=" + write, runtime);
        log.info("最终判定: success={} 原因={} 判定依据=内置默认是出厂基线，运行时改写必须拒绝且原因码 BUILTIN_LAYER_READONLY",
                write.success(), runtime.lastFailureReason().orElse(""));
        assertFalse(write.success());
        assertTrue(runtime.lastFailureReason().orElse("").contains("BUILTIN_LAYER_READONLY"));

        // 尝试清空内置默认层 -> 同样拒绝
        Map<ConfigLayer, Map<String, String>> clearReq = submit(ConfigLayer.BUILTIN_DEFAULT, Map.of());
        UpdateOutcome clear = runtime.update(clearReq, Map.of());
        logState("清空内置层输入=" + clearReq + " outcome=" + clear, runtime);
        assertFalse(clear.success());
        assertTrue(runtime.lastFailureReason().orElse("").contains("BUILTIN_LAYER_READONLY"));

        // 版本与状态原样，且该原因与取值非法(ILLEGAL_VALUE)可区分
        assertEquals(v0, runtime.currentVersion());
        assertEquals(before.layers(), runtime.current().layers());
        assertEquals(before.config(), runtime.current().config());
        assertFalse(runtime.lastFailureReason().orElse("").contains("ILLEGAL_VALUE"));

        // 对照：非法取值的原因码确实不同
        UpdateOutcome illegal = runtime.update(
                submit(ConfigLayer.RUNTIME_INJECTION, Map.of("max.connections", "-1")), Map.of());
        log.info("对照输入=max.connections=-1 outcome={} 判定依据=ILLEGAL_VALUE 与 BUILTIN_LAYER_READONLY 可区分", illegal);
        assertTrue(runtime.lastFailureReason().orElse("").contains("ILLEGAL_VALUE"));
        assertFalse(runtime.lastFailureReason().orElse("").contains("BUILTIN_LAYER_READONLY"));
    }

    @Test
    void concurrentReadersSeeConsistentLayeredState() throws Exception {
        HotSwapRuntime runtime = new HotSwapRuntime();
        int rounds = 150;
        int readers = 8;
        AtomicInteger inconsistencies = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);

        // 写线程：交替更新两个可写层并周期性清空高优先层
        Future<?> writer = pool.submit(() -> {
            try {
                start.await();
                for (int n = 1; n <= rounds; n++) {
                    runtime.update(submit(ConfigLayer.EXTERNAL_FILE,
                            Map.of("max.connections", String.valueOf(100 + n))), Map.of());
                    runtime.update(submit(ConfigLayer.RUNTIME_INJECTION,
                            Map.of("max.connections", String.valueOf(1000 + n))), Map.of());
                    if (n % 3 == 0) {
                        runtime.update(submit(ConfigLayer.RUNTIME_INJECTION, Map.of()), Map.of());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 读线程：校验任一快照内 账本/来源说明/生效值 三者一致且版本单调
        List<Future<?>> readerFutures = new ArrayList<>();
        for (int r = 0; r < readers; r++) {
            readerFutures.add(pool.submit(() -> {
                try {
                    start.await();
                    long lastVersion = 0;
                    while (!writer.isDone()) {
                        RuntimeSnapshot snap = runtime.current();
                        ConfigLayer winner = snap.winningLayerOf("max.connections");
                        // 来源说明必须是保存该键的最高层
                        ConfigLayer highest = null;
                        for (ConfigLayer layer : List.of(ConfigLayer.RUNTIME_INJECTION,
                                ConfigLayer.EXTERNAL_FILE, ConfigLayer.BUILTIN_DEFAULT)) {
                            if (snap.layerEntries(layer).containsKey("max.connections")) {
                                highest = layer;
                                break;
                            }
                        }
                        ConfigLayer expected = highest != null ? highest : ConfigLayer.BUILTIN_DEFAULT;
                        boolean consistent = winner == expected;
                        // 若胜者层保存了该键，其值必须等于生效值（生效值与来源说明对得上）
                        String raw = snap.layerEntries(winner).get("max.connections");
                        if (raw != null) {
                            consistent &= Integer.parseInt(raw) == snap.config().maxConnections();
                        }
                        if (!consistent) {
                            inconsistencies.incrementAndGet();
                            log.error("读到中间状态: version={} winner={} expected={} layers={} config={}",
                                    snap.version(), winner, expected, snap.layers(), snap.config());
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
        log.info("用例=并发读分层一致性 输入=1写线程x{}轮(交替更新EXTERNAL_FILE/RUNTIME_INJECTION并周期性清空), "
                + "{}读线程 判定依据=任一快照内 provenance==保存该键的最高层 且胜者层取值==生效值 且版本单调", rounds, readers);
        start.countDown();
        writer.get(30, TimeUnit.SECONDS);
        for (Future<?> f : readerFutures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        logState("结束", runtime);
        log.info("输出: 最终版本={} 不一致次数={}", runtime.currentVersion(), inconsistencies.get());
        assertEquals(0, inconsistencies.get(), "并发读绝不能看到分层账本与生效值对不上的中间状态");
    }
}
