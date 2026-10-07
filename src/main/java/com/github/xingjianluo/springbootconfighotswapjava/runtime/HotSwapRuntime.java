package com.github.xingjianluo.springbootconfighotswapjava.runtime;

import com.github.xingjianluo.springbootconfighotswapjava.config.ConfigAssembler;
import com.github.xingjianluo.springbootconfighotswapjava.config.ConfigIssue;
import com.github.xingjianluo.springbootconfighotswapjava.config.ConfigLayer;
import com.github.xingjianluo.springbootconfighotswapjava.config.LayeredConfigState;
import com.github.xingjianluo.springbootconfighotswapjava.ext.DefaultGreetingService;
import com.github.xingjianluo.springbootconfighotswapjava.ext.FormalGreetingService;
import com.github.xingjianluo.springbootconfighotswapjava.ext.GreetingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 热更新运行时。
 *
 * <p>分层账本语义：</p>
 * <ul>
 *   <li>三个来源层（{@link ConfigLayer}）各自独立长期保存内容，一轮更新只替换本次提交的层，
 *       未提及的层保持上一次保存的内容；</li>
 *   <li>空内容表示显式清空该层（该层不再提供取值），与"未提及"（无变化）严格区分；
 *       清空高优先层后，最终值立即回退到低优先层保存的取值；</li>
 *   <li>最终生效值永远由三层整体按固定优先级（内置默认 &lt; 外部文件 &lt; 运行时注入）重新合并，
 *       高优先层已保存的取值不会因低优先层后来的更新而被覆盖；</li>
 *   <li>内置默认层是出厂基线，运行时请求不能改写，尝试改写以
 *       {@code BUILTIN_LAYER_READONLY} 明确拒绝。</li>
 * </ul>
 *
 * <p>并发语义：</p>
 * <ul>
 *   <li>读侧无锁：{@link #current()} 返回不可变快照的整体引用，并发读永远看到完整版本，
 *       分层账本、生效配置与来源说明互相对得上；</li>
 *   <li>写侧串行：{@link #update} 以 monitor 串行化，同一时刻的重复/冲突更新按到达顺序逐个生效或失败；</li>
 *   <li>原子生效：新快照先完整构建再一次性替换引用；</li>
 *   <li>失败回退：校验失败（含清空后必填缺失/取值非法）或扩展缺失时保持旧快照不变，
 *       版本与当前状态原样保留，并记录可区分的失败原因。</li>
 * </ul>
 */
@Component
public class HotSwapRuntime {

    private static final Logger log = LoggerFactory.getLogger(HotSwapRuntime.class);

    private final ConfigAssembler assembler = new ConfigAssembler();
    private final AtomicReference<RuntimeSnapshot> current;
    private volatile String lastFailureReason;

    public HotSwapRuntime() {
        Map<String, GreetingService> exts = new HashMap<>();
        exts.put(DefaultGreetingService.ID, new DefaultGreetingService());
        exts.put(FormalGreetingService.ID, new FormalGreetingService());
        // 出厂基线：仅内置默认层有内容，其余两层为空账本
        LayeredConfigState bootLayers = LayeredConfigState.of(Map.of(
                ConfigLayer.BUILTIN_DEFAULT, Map.of(
                        ConfigAssembler.KEY_GREETING_PREFIX, "Hello",
                        ConfigAssembler.KEY_GREETING_IMPL, DefaultGreetingService.ID)));
        ConfigAssembler.AssembleResult result = assembler.assemble(bootLayers.layers());
        if (result.hasFatal()) {
            throw new IllegalStateException("内置默认配置非法: " + result.issues());
        }
        this.current = new AtomicReference<>(new RuntimeSnapshot(
                1L, Instant.now(), result.config(), bootLayers, result.provenance(), exts));
    }

    /** 当前生效快照（读侧无锁，始终看到完整版本）。 */
    public RuntimeSnapshot current() {
        return current.get();
    }

    /**
     * 串行化地应用一次更新。
     *
     * @param layers 本次提交的分层配置：出现的层以其内容整体替换该层账本
     *               （空 map 表示显式清空该层）；未出现的层保持原内容。
     *               不允许包含 {@link ConfigLayer#BUILTIN_DEFAULT}（出厂基线只读）。
     * @param newExtensions 要注册/替换的扩展实现（按 id 覆盖），可为空
     * @return 成功时携带新版本号；失败时携带当前（旧）版本号与可区分失败原因
     */
    public synchronized UpdateOutcome update(Map<ConfigLayer, Map<String, String>> layers,
                                             Map<String, GreetingService> newExtensions) {
        RuntimeSnapshot before = current.get();
        List<String> warnings = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        // 0. 内置默认层只读：运行时请求不得改写出厂基线
        if (layers.containsKey(ConfigLayer.BUILTIN_DEFAULT)) {
            reasons.add("BUILTIN_LAYER_READONLY[" + ConfigLayer.BUILTIN_DEFAULT
                    + "]: 内置默认层为出厂基线，运行时请求不能改写");
            return fail(before, reasons, warnings);
        }

        // 1. 分层账本演进：只替换本次提交的层（空内容=清空），其余层保持上次保存的内容
        LayeredConfigState nextLayers = before.layers();
        for (Map.Entry<ConfigLayer, Map<String, String>> e : layers.entrySet()) {
            nextLayers = nextLayers.withLayer(e.getKey(), e.getValue());
        }

        // 2. 三层整体按固定优先级重新合并校验（清空导致的必填缺失/取值非法在此暴露）
        ConfigAssembler.AssembleResult result = assembler.assemble(nextLayers.layers());
        for (ConfigIssue issue : result.issues()) {
            String text = issue.kind() + "[" + issue.key() + "@" + issue.layer() + "]: " + issue.message();
            (issue.isFatal() ? reasons : warnings).add(text);
        }
        if (result.hasFatal()) {
            return fail(before, reasons, warnings);
        }

        // 3. 合并扩展注册表（新实现按 id 替换旧实现）
        Map<String, GreetingService> exts = new HashMap<>(before.extensions());
        if (newExtensions != null) {
            for (GreetingService svc : newExtensions.values()) {
                if (svc == null || svc.id() == null || svc.id().isBlank()) {
                    reasons.add("EXTENSION_INVALID: 扩展实现或 id 为空");
                    return fail(before, reasons, warnings);
                }
                exts.put(svc.id(), svc);
            }
        }

        // 4. 配置引用的扩展必须存在，否则拒绝并回退
        if (!exts.containsKey(result.config().greetingImpl())) {
            reasons.add("EXTENSION_MISSING[" + result.config().greetingImpl()
                    + "]: 配置引用的问候扩展未注册");
            return fail(before, reasons, warnings);
        }

        // 5. 原子发布新快照（账本、生效配置、来源说明同属一个版本）
        RuntimeSnapshot after = new RuntimeSnapshot(before.version() + 1, Instant.now(),
                result.config(), nextLayers, result.provenance(), exts);
        current.set(after);
        log.info("热更新生效: version={} config={} layers={} extensions={}",
                after.version(), after.config(), after.layers().layers(), exts.keySet());
        return UpdateOutcome.ok(after.version(), warnings);
    }

    private UpdateOutcome fail(RuntimeSnapshot before, List<String> reasons, List<String> warnings) {
        String reason = String.join("; ", reasons);
        this.lastFailureReason = reason;
        log.warn("热更新被拒绝并回退: 保持版本={}, 原因={}", before.version(), reason);
        return UpdateOutcome.failed(before.version(), reasons, warnings);
    }

    public long currentVersion() {
        return current.get().version();
    }

    public Optional<String> lastFailureReason() {
        return Optional.ofNullable(lastFailureReason);
    }
}
