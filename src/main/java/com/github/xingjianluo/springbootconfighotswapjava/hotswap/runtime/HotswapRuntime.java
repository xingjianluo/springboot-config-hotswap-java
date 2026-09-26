package com.github.xingjianluo.springbootconfighotswapjava.hotswap.runtime;

import com.github.xingjianluo.springbootconfighotswapjava.hotswap.config.AppConfig;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.config.ConfigIssue;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.config.ConfigLayer;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.config.ConfigResolution;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.config.LayeredConfigResolver;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.extension.DefaultGreetingExtension;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.extension.ExtensionSnapshot;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.extension.GreetingExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 热更新运行时。
 *
 * <p>语义：
 * <ul>
 *   <li>状态整体不可变，读取通过 {@link AtomicReference} 获得某一个完整版本，
 *       更新过程中并发读取绝不会看到中间态；</li>
 *   <li>所有更新在 {@link ReentrantLock} 下串行执行，并发/重复更新不会交错；</li>
 *   <li>更新先完整校验再原子替换；任何失败保持旧版本不变，并记录可区分的失败原因。</li>
 * </ul>
 */
@Component
public class HotswapRuntime {

    private static final Logger log = LoggerFactory.getLogger(HotswapRuntime.class);

    private final LayeredConfigResolver resolver = new LayeredConfigResolver();

    /** 各层当前生效的原始配置（仅内置默认之外的层会被 updateConfig 修改）。 */
    private final Map<ConfigLayer, Map<String, String>> layers = new EnumMap<>(ConfigLayer.class);

    private final AtomicReference<RuntimeState> state;
    private final AtomicReference<UpdateFailure> lastFailure = new AtomicReference<>();
    private final ReentrantLock updateLock = new ReentrantLock();

    public HotswapRuntime() {
        layers.put(ConfigLayer.BUILTIN_DEFAULT, Map.of(
                LayeredConfigResolver.KEY_SCHEMA_VERSION, String.valueOf(AppConfig.CURRENT_SCHEMA_VERSION),
                LayeredConfigResolver.KEY_GREETING, "Hello",
                LayeredConfigResolver.KEY_GREETING_EXTENSION, DefaultGreetingExtension.ID,
                LayeredConfigResolver.KEY_RATE_LIMIT, "100",
                LayeredConfigResolver.KEY_FEATURE_X, "false"));
        layers.put(ConfigLayer.EXTERNAL_FILE, Map.of());
        layers.put(ConfigLayer.RUNTIME_INJECTED, Map.of());

        ConfigResolution initial = resolver.resolve(layers);
        if (!initial.accepted()) {
            throw new IllegalStateException("内置默认配置必须可用: " + initial.rejections());
        }
        ExtensionSnapshot extensions = new ExtensionSnapshot(
                Map.of(DefaultGreetingExtension.ID, new DefaultGreetingExtension()));
        this.state = new AtomicReference<>(
                new RuntimeState(1L, initial.config(), extensions, Instant.now()));
        log.info("热更新运行时初始化完成, version=1, config={}", initial.config());
    }

    /** 当前生效状态（任意读取都看到一个完整版本）。 */
    public RuntimeState currentState() {
        return state.get();
    }

    /** 可观测状态：版本、生效时间、最近失败原因。 */
    public RuntimeStatus status() {
        RuntimeState s = state.get();
        return new RuntimeStatus(s.version(), s.appliedAt(), lastFailure.get());
    }

    /**
     * 提交一次配置更新：替换指定层的取值后与各层按优先级合并。
     * 串行执行；校验失败则保持旧版本并记录失败原因。
     */
    public UpdateResult updateConfig(ConfigLayer layer, Map<String, String> values) {
        if (layer == ConfigLayer.BUILTIN_DEFAULT) {
            UpdateFailure failure = new UpdateFailure(UpdateFailure.Reason.CONFIG_REJECTED,
                    List.of("内置默认层不允许在运行时修改"), Instant.now());
            lastFailure.set(failure);
            return UpdateResult.failed(state.get().version(), failure);
        }
        updateLock.lock();
        try {
            RuntimeState before = state.get();
            Map<ConfigLayer, Map<String, String>> candidate = new EnumMap<>(layers);
            candidate.put(layer, Map.copyOf(values));

            ConfigResolution resolution = resolver.resolve(candidate);
            if (!resolution.accepted()) {
                return recordFailure(before, UpdateFailure.Reason.CONFIG_REJECTED,
                        resolution.rejections().stream().map(ConfigIssue::toString).toList());
            }
            AppConfig config = resolution.config();
            if (!before.extensions().contains(config.greetingExtension())) {
                return recordFailure(before, UpdateFailure.Reason.UNKNOWN_EXTENSION,
                        List.of("配置引用了未注册的扩展: " + config.greetingExtension()
                                + "，可用: " + before.extensions().extensions().keySet()));
            }

            layers.put(layer, Map.copyOf(values));
            RuntimeState after = new RuntimeState(
                    before.version() + 1, config, before.extensions(), Instant.now());
            state.set(after);
            List<String> warnings = resolution.warnings().stream().map(ConfigIssue::toString).toList();
            log.info("配置热更新生效: version {} -> {}, layer={}, warnings={}",
                    before.version(), after.version(), layer, warnings);
            return UpdateResult.ok(after.version(), warnings);
        } finally {
            updateLock.unlock();
        }
    }

    /**
     * 注册/替换扩展实现，原子生效；不得移除当前配置正在引用的扩展。
     */
    public UpdateResult replaceExtensions(List<GreetingExtension> extensions) {
        updateLock.lock();
        try {
            RuntimeState before = state.get();

            Map<String, GreetingExtension> map = new LinkedHashMap<>();
            List<String> duplicates = new ArrayList<>();
            for (GreetingExtension ext : extensions) {
                if (map.put(ext.id(), ext) != null) {
                    duplicates.add(ext.id());
                }
            }
            if (!duplicates.isEmpty()) {
                return recordFailure(before, UpdateFailure.Reason.EXTENSION_CONFLICT,
                        List.of("扩展 id 重复: " + duplicates));
            }
            String inUse = before.config().greetingExtension();
            if (!map.containsKey(inUse)) {
                return recordFailure(before, UpdateFailure.Reason.UNKNOWN_EXTENSION,
                        List.of("新扩展集合缺少当前配置正在使用的扩展: " + inUse));
            }

            RuntimeState after = new RuntimeState(
                    before.version() + 1, before.config(), new ExtensionSnapshot(map), Instant.now());
            state.set(after);
            log.info("扩展热更新生效: version {} -> {}, extensions={}",
                    before.version(), after.version(), map.keySet());
            return UpdateResult.ok(after.version(), List.of());
        } finally {
            updateLock.unlock();
        }
    }

    private UpdateResult recordFailure(RuntimeState current, UpdateFailure.Reason reason, List<String> details) {
        UpdateFailure failure = new UpdateFailure(reason, List.copyOf(details), Instant.now());
        lastFailure.set(failure);
        log.warn("热更新被拒绝并回退: version 保持 {}, reason={}, details={}",
                current.version(), reason, details);
        return UpdateResult.failed(current.version(), failure);
    }
}
