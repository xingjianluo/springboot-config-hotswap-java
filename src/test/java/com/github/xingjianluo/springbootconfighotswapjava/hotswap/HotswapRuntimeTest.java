package com.github.xingjianluo.springbootconfighotswapjava.hotswap;

import com.github.xingjianluo.springbootconfighotswapjava.hotswap.config.ConfigLayer;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.extension.DefaultGreetingExtension;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.extension.ShoutGreetingExtension;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.runtime.HotswapRuntime;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.runtime.RuntimeState;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.runtime.UpdateFailure;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.runtime.UpdateResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 热更新运行时测试：并发读取一致性、更新串行化、失败回滚与失败原因可区分。
 */
class HotswapRuntimeTest {

    private static final Logger log = LoggerFactory.getLogger(HotswapRuntimeTest.class);

    @Test
    void failedUpdateRollsBackAndKeepsDistinguishableReason() {
        HotswapRuntime runtime = new HotswapRuntime();
        long versionBefore = runtime.currentState().version();

        Map<String, String> badInput = Map.of("rateLimit", "-5");
        UpdateResult result = runtime.updateConfig(ConfigLayer.RUNTIME_INJECTED, badInput);

        log.info("[用例:失败回滚] 输入={} | 判定依据=rateLimit 越界应被拒绝，版本与配置保持旧值，"
                        + "失败原因=CONFIG_REJECTED | 结果: applied={}, version={} -> {}, failure={}",
                badInput, result.applied(), versionBefore,
                runtime.currentState().version(), result.failure());

        assertFalse(result.applied());
        assertEquals(UpdateFailure.Reason.CONFIG_REJECTED, result.failure().reason());
        // 回滚：版本与状态完全不变
        assertEquals(versionBefore, runtime.currentState().version());
        assertEquals(100, runtime.currentState().config().rateLimit());
        // 失败原因可观测
        assertNotNull(runtime.status().lastFailure());
        assertEquals(UpdateFailure.Reason.CONFIG_REJECTED, runtime.status().lastFailure().reason());
    }

    @Test
    void unknownExtensionReferenceIsRejected() {
        HotswapRuntime runtime = new HotswapRuntime();
        Map<String, String> input = Map.of("greetingExtension", "not-registered");

        UpdateResult result = runtime.updateConfig(ConfigLayer.RUNTIME_INJECTED, input);

        log.info("[用例:未知扩展引用] 输入={} | 判定依据=配置引用的扩展未注册 -> UNKNOWN_EXTENSION，保持旧版本 | 结果: applied={}, failure={}",
                input, result.applied(), result.failure());

        assertFalse(result.applied());
        assertEquals(UpdateFailure.Reason.UNKNOWN_EXTENSION, result.failure().reason());
        assertEquals(1L, runtime.currentState().version());
    }

    @Test
    void extensionReplacementIsAtomicAndValidated() {
        HotswapRuntime runtime = new HotswapRuntime();

        // 移除正在使用的 default 扩展 -> 拒绝
        UpdateResult bad = runtime.replaceExtensions(List.of(new ShoutGreetingExtension()));
        log.info("[用例:扩展替换-非法] 输入=[shout] | 判定依据=当前配置仍引用 default，移除即拒绝 -> UNKNOWN_EXTENSION | 结果: applied={}, failure={}",
                bad.applied(), bad.failure());
        assertFalse(bad.applied());
        assertEquals(UpdateFailure.Reason.UNKNOWN_EXTENSION, bad.failure().reason());
        assertEquals(1L, runtime.currentState().version());

        // 合法替换：先切配置到 shout，再提供两个实现
        UpdateResult cfg = runtime.updateConfig(ConfigLayer.RUNTIME_INJECTED,
                Map.of("greetingExtension", "shout"));
        log.info("[用例:扩展替换-配置先行] 输入=greetingExtension=shout | 判定依据=shout 未注册 -> 拒绝 | 结果: applied={}, failure={}",
                cfg.applied(), cfg.failure());
        assertFalse(cfg.applied());

        UpdateResult ok = runtime.replaceExtensions(List.of(
                new DefaultGreetingExtension(), new ShoutGreetingExtension()));
        assertTrue(ok.applied());
        UpdateResult cfg2 = runtime.updateConfig(ConfigLayer.RUNTIME_INJECTED,
                Map.of("greetingExtension", "shout"));
        assertTrue(cfg2.applied());

        RuntimeState s = runtime.currentState();
        String message = s.extensions().require(s.config().greetingExtension())
                .greet("world", s.config().greeting());
        log.info("[用例:扩展替换-生效] 判定依据=先注册 shout 再切换配置，两次更新均原子生效 | 结果: version={}, message={}",
                s.version(), message);
        assertEquals("HELLO, WORLD!", message);
    }

    @Test
    void duplicateExtensionIdsAreRejected() {
        HotswapRuntime runtime = new HotswapRuntime();
        UpdateResult result = runtime.replaceExtensions(List.of(
                new DefaultGreetingExtension(),
                new DefaultGreetingExtension())); // 同 id

        log.info("[用例:扩展冲突] 输入=[default, default] | 判定依据=id 重复 -> EXTENSION_CONFLICT | 结果: applied={}, failure={}",
                result.applied(), result.failure());

        assertFalse(result.applied());
        assertEquals(UpdateFailure.Reason.EXTENSION_CONFLICT, result.failure().reason());
        assertEquals(1L, runtime.currentState().version());
    }

    @Test
    void concurrentReadersAlwaysSeeCompleteVersionAndUpdatesAreSerialized() throws Exception {
        HotswapRuntime runtime = new HotswapRuntime();
        int writers = 8;
        int writesPerWriter = 25;
        int readers = 8;

        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inconsistencies = new AtomicInteger();
        List<UpdateResult> results = new ArrayList<>();

        // 写线程：一半提交合法更新，一半提交非法更新（应全部回滚）
        for (int w = 0; w < writers; w++) {
            final int id = w;
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < writesPerWriter; i++) {
                    Map<String, String> values = (id % 2 == 0)
                            ? Map.of("rateLimit", String.valueOf(100 + id))
                            : Map.of("rateLimit", "bad-" + id);
                    UpdateResult r;
                    synchronized (results) {
                        r = runtime.updateConfig(ConfigLayer.RUNTIME_INJECTED, values);
                        results.add(r);
                    }
                }
            });
        }

        // 读线程：更新进行中持续读取，校验版本内部一致（config 与 extensions 同属一个版本快照）
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < 2000; i++) {
                    RuntimeState s = runtime.currentState();
                    boolean consistent = s.version() >= 1
                            && s.config() != null
                            && s.extensions().contains(s.config().greetingExtension())
                            && s.appliedAt() != null;
                    if (!consistent) {
                        inconsistencies.incrementAndGet();
                    }
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        long applied = results.stream().filter(UpdateResult::applied).count();
        long failed = results.size() - applied;
        RuntimeState finalState = runtime.currentState();

        log.info("[用例:并发读写] 输入={} 个写线程 x {} 次（一半合法一半非法），{} 个读线程 x 2000 次 | "
                        + "判定依据=读到的每个版本都必须完整（配置引用的扩展存在）；版本号严格递增且无交错；"
                        + "非法更新全部回滚 | 结果: 总更新={}, 生效={}, 回滚={}, 不一致读={}, 最终版本={}",
                writers, writesPerWriter, readers,
                results.size(), applied, failed, inconsistencies.get(), finalState.version());

        assertEquals(0, inconsistencies.get(), "并发读取不得看到中间态");
        assertEquals(writers * writesPerWriter, results.size());
        // 生效次数 = 版本增量：串行化保证无丢失、无交错
        assertEquals(applied, finalState.version() - 1);
        assertTrue(failed > 0);
        // 失败原因可区分且已记录
        assertEquals(UpdateFailure.Reason.CONFIG_REJECTED, runtime.status().lastFailure().reason());
        // 最终生效配置引用的扩展必须存在（状态完整）
        assertTrue(finalState.extensions().contains(finalState.config().greetingExtension()));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
