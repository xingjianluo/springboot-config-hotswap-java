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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 热更新运行时。
 *
 * <p>分层账本语义：</p>
 * <ul>
 *   <li>三个来源层（{@link ConfigLayer}）各自独立长期保存：一轮更新只替换本次提交的层，
 *       未提交的层保持上一次保存的内容；</li>
 *   <li>提交空内容表示<strong>清空该层</strong>（而非无变化），清空后最终值立即回退到
 *       更低层能提供的取值；</li>
 *   <li>最终生效值始终按 BUILTIN_DEFAULT &lt; EXTERNAL_FILE &lt; RUNTIME_INJECTION 的固定
 *       顺序合并，高层的既有取值不会因为后来更新低层而被覆盖；</li>
 *   <li>{@link ConfigLayer#BUILTIN_DEFAULT} 是出厂基线，运行时请求不得改写或清空，
 *       尝试将被以可区分原因（BUILTIN_LAYER_READONLY）明确拒绝。</li>
 * </ul>
 *
 * <p>并发与失败语义：</p>
 * <ul>
 *   <li>读侧无锁：{@link #current()} 返回不可变快照的整体引用，并发读永远看到完整版本；</li>
 *   <li>写侧串行：{@link #update} 以 monitor 串行化，不会产生交错结果；</li>
 *   <li>原子生效：新快照（含分层账本与来源说明）先完整构建再一次性替换引用；</li>
 *   <li>失败回退：校验失败、清空导致必填缺失/取值非法、扩展缺失或试图改写内置层时，
 *       版本与当前状态保持原样，并记录可互相区分的失败原因。</li>
 * </ul>
 */
@Component
public class HotSwapRuntime {

    private static final Logger log = LoggerFactory.getLogger(HotSwapRuntime.class);

    private final ConfigAssembler assembler = new ConfigAssembler();
    private final AtomicReference<RuntimeSnapshot> current;
    private volatile String lastFailureReason;

    public HotSwapRuntime() {
        this(defaultBootLayers());
    }

    /**
     * 以指定出厂分层账本启动（测试可构造内置层不含必填项的初始状态，
     * 以验证"清空导致必填缺失"的回滚路径）。出厂账本本身必须能合并出合法配置。
     */
    public HotSwapRuntime(Map<ConfigLayer, Map<String, String>> bootLayers) {
        Map<String, GreetingService> exts = new HashMap<>();
        exts.put(DefaultGreetingService.ID, new DefaultGreetingService());
        exts.put(FormalGreetingService.ID, new FormalGreetingService());
        ConfigAssembler.AssembleResult result = assembler.assemble(bootLayers);
        if (result.hasFatal()) {
            throw new IllegalStateException("内置默认配置非法: " + result.issues());
        }
        this.current = new AtomicReference<>(new RuntimeSnapshot(1L, Instant.now(), result.config(),
                exts, bootLayers, canonicalProvenance(result)));
    }

    private static Map<ConfigLayer, Map<String, String>> defaultBootLayers() {
        Map<ConfigLayer, Map<String, String>> bootLayers = new EnumMap<>(ConfigLayer.class);
        bootLayers.put(ConfigLayer.BUILTIN_DEFAULT, Map.of(
                ConfigAssembler.KEY_GREETING_PREFIX, "Hello",
                ConfigAssembler.KEY_GREETING_IMPL, DefaultGreetingService.ID));
        return bootLayers;
    }

    /** 当前生效快照（读侧无锁，始终看到完整版本：分层账本、生效值与来源说明同属一个版本）。 */
    public RuntimeSnapshot current() {
        return current.get();
    }

    /**
     * 串行化地应用一次更新。
     *
     * @param layers 本次提交的分层内容：只替换出现的层；某层提交空表表示清空该层；
     *               未出现的层保持原样；不允许提交 BUILTIN_DEFAULT 层
     * @param newExtensions 要注册/替换的扩展实现（按 id 覆盖），可为空
     * @return 成功时携带新版本号；失败时携带当前（旧）版本号与可区分失败原因
     */
    public synchronized UpdateOutcome update(Map<ConfigLayer, Map<String, String>> layers,
                                             Map<String, GreetingService> newExtensions) {
        RuntimeSnapshot before = current.get();
        List<String> warnings = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        // 0. 内置默认层为出厂基线，运行时请求不得改写或清空
        if (layers.containsKey(ConfigLayer.BUILTIN_DEFAULT)) {
            reasons.add("BUILTIN_LAYER_READONLY[" + ConfigLayer.BUILTIN_DEFAULT
                    + "]: 内置默认层为出厂基线，运行时请求不得改写或清空");
            return fail(before, reasons, warnings);
        }

        // 1. 分层账本：仅替换本次提交的层（空表=清空该层），其余层保持上次保存的内容
        Map<ConfigLayer, Map<String, String>> newLayers = new EnumMap<>(ConfigLayer.class);
        before.layers().forEach((layer, entries) -> newLayers.put(layer, new HashMap<>(entries)));
        for (Map.Entry<ConfigLayer, Map<String, String>> e : layers.entrySet()) {
            Map<String, String> submitted = e.getValue() == null ? Map.of() : e.getValue();
            newLayers.put(e.getKey(), new HashMap<>(submitted));
        }

        // 2. 按固定优先级合并全部账本并校验（清空导致的必填缺失/取值非法在此暴露）
        ConfigAssembler.AssembleResult result = assembler.assemble(newLayers);
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

        // 5. 原子发布新快照（分层账本、生效配置、来源说明同属一个版本）
        RuntimeSnapshot after = new RuntimeSnapshot(before.version() + 1, Instant.now(), result.config(),
                exts, newLayers, canonicalProvenance(result));
        current.set(after);
        log.info("热更新生效: version={} config={} layers={} provenance={} extensions={}",
                after.version(), after.config(), after.layers(), after.provenance(), exts.keySet());
        return UpdateOutcome.ok(after.version(), warnings);
    }

    /**
     * 每个规范配置项最终采用的来源层：出现在某层账本中的取合并胜者所在层，
     * 未出现在任何层（走内置缺省值）的记为 BUILTIN_DEFAULT。
     */
    private static Map<String, ConfigLayer> canonicalProvenance(ConfigAssembler.AssembleResult result) {
        Map<String, ConfigLayer> provenance = new LinkedHashMap<>();
        provenance.put(ConfigAssembler.KEY_SCHEMA_VERSION,
                sourceOrDefault(result, ConfigAssembler.KEY_SCHEMA_VERSION));
        provenance.put(ConfigAssembler.KEY_GREETING_PREFIX,
                sourceOrDefault(result, ConfigAssembler.KEY_GREETING_PREFIX));
        provenance.put(ConfigAssembler.KEY_MAX_CONNECTIONS,
                sourceOrDefault(result, ConfigAssembler.KEY_MAX_CONNECTIONS));
        ConfigLayer featureSource = result.provenance().get(ConfigAssembler.KEY_FEATURE_X_ENABLED);
        if (featureSource == null) {
            // 旧键别名兼容时，来源记到旧键实际出现的层
            featureSource = result.provenance().get(ConfigAssembler.LEGACY_KEY_FEATURE_ENABLED);
        }
        provenance.put(ConfigAssembler.KEY_FEATURE_X_ENABLED,
                featureSource != null ? featureSource : ConfigLayer.BUILTIN_DEFAULT);
        provenance.put(ConfigAssembler.KEY_GREETING_IMPL,
                sourceOrDefault(result, ConfigAssembler.KEY_GREETING_IMPL));
        return provenance;
    }

    private static ConfigLayer sourceOrDefault(ConfigAssembler.AssembleResult result, String key) {
        ConfigLayer layer = result.provenance().get(key);
        return layer != null ? layer : ConfigLayer.BUILTIN_DEFAULT;
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
