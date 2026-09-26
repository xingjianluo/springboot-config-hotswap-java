package com.github.xingjianluo.springbootconfighotswapjava.runtime;

import com.github.xingjianluo.springbootconfighotswapjava.config.ConfigAssembler;
import com.github.xingjianluo.springbootconfighotswapjava.config.ConfigIssue;
import com.github.xingjianluo.springbootconfighotswapjava.config.ConfigLayer;
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
 * <p>语义：</p>
 * <ul>
 *   <li>读侧无锁：{@link #current()} 返回不可变快照的整体引用，并发读永远看到完整版本；</li>
 *   <li>写侧串行：{@link #update} 以 monitor 串行化，同一时刻的重复/冲突更新按到达顺序逐个生效或失败，
 *       不会产生交错结果；</li>
 *   <li>原子生效：新快照先完整构建再一次性替换引用；</li>
 *   <li>失败回退：校验失败或扩展缺失时保持旧快照不变，并记录可区分的失败原因。</li>
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
        Map<ConfigLayer, Map<String, String>> bootLayers = Map.of(
                ConfigLayer.BUILTIN_DEFAULT, Map.of(
                        ConfigAssembler.KEY_GREETING_PREFIX, "Hello",
                        ConfigAssembler.KEY_GREETING_IMPL, DefaultGreetingService.ID));
        ConfigAssembler.AssembleResult result = assembler.assemble(bootLayers);
        if (result.hasFatal()) {
            throw new IllegalStateException("内置默认配置非法: " + result.issues());
        }
        this.current = new AtomicReference<>(new RuntimeSnapshot(1L, Instant.now(), result.config(), exts));
    }

    /** 当前生效快照（读侧无锁，始终看到完整版本）。 */
    public RuntimeSnapshot current() {
        return current.get();
    }

    /**
     * 串行化地应用一次更新。
     *
     * @param layers 分层配置（可只给有变化的层；未提及的字段沿用当前生效配置作为基底）
     * @param newExtensions 要注册/替换的扩展实现（按 id 覆盖），可为空
     * @return 成功时携带新版本号；失败时携带当前（旧）版本号与可区分失败原因
     */
    public synchronized UpdateOutcome update(Map<ConfigLayer, Map<String, String>> layers,
                                             Map<String, GreetingService> newExtensions) {
        RuntimeSnapshot before = current.get();

        // 1. 以当前生效配置为基底，叠加本次各层输入后合并校验
        Map<ConfigLayer, Map<String, String>> effective = new java.util.EnumMap<>(ConfigLayer.class);
        Map<String, String> base = new HashMap<>(before.config().asMap());
        Map<String, String> callerDefault = layers.get(ConfigLayer.BUILTIN_DEFAULT);
        if (callerDefault != null) {
            base.putAll(callerDefault);
        }
        effective.put(ConfigLayer.BUILTIN_DEFAULT, base);
        for (ConfigLayer layer : ConfigLayer.values()) {
            if (layer != ConfigLayer.BUILTIN_DEFAULT && layers.containsKey(layer)) {
                effective.put(layer, layers.get(layer));
            }
        }
        ConfigAssembler.AssembleResult result = assembler.assemble(effective);
        List<String> warnings = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (ConfigIssue issue : result.issues()) {
            String text = issue.kind() + "[" + issue.key() + "@" + issue.layer() + "]: " + issue.message();
            (issue.isFatal() ? reasons : warnings).add(text);
        }
        if (result.hasFatal()) {
            return fail(before, reasons, warnings);
        }

        // 2. 合并扩展注册表（新实现按 id 替换旧实现）
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

        // 3. 配置引用的扩展必须存在，否则拒绝并回退
        if (!exts.containsKey(result.config().greetingImpl())) {
            reasons.add("EXTENSION_MISSING[" + result.config().greetingImpl()
                    + "]: 配置引用的问候扩展未注册");
            return fail(before, reasons, warnings);
        }

        // 4. 原子发布新快照
        RuntimeSnapshot after = new RuntimeSnapshot(before.version() + 1, Instant.now(), result.config(), exts);
        current.set(after);
        log.info("热更新生效: version={} config={} extensions={}", after.version(), after.config(), exts.keySet());
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
