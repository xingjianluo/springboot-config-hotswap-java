package com.github.xingjianluo.springbootconfighotswapjava.runtime;

import com.github.xingjianluo.springbootconfighotswapjava.config.AppConfig;
import com.github.xingjianluo.springbootconfighotswapjava.config.ConfigLayer;
import com.github.xingjianluo.springbootconfighotswapjava.ext.GreetingService;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 某一版本的完整运行时状态（不可变）。
 *
 * <p>除了生效配置与扩展注册表，还长期保存三个来源层各自的账本内容
 * （{@code layers}，空账本表示该层已被清空）以及每个配置项最终采用的来源层
 * （{@code provenance}）。读取方通过整体引用切换，只会看到某一个完整版本，
 * 绝不会看到"某一层改了另一层没改"或"生效值与来源说明对不上"的中间状态。</p>
 */
public record RuntimeSnapshot(
        long version,
        Instant effectiveAt,
        AppConfig config,
        Map<String, GreetingService> extensions,
        Map<ConfigLayer, Map<String, String>> layers,
        Map<String, ConfigLayer> provenance) {

    public RuntimeSnapshot {
        extensions = Map.copyOf(extensions);
        Map<ConfigLayer, Map<String, String>> layersCopy = new EnumMap<>(ConfigLayer.class);
        layers.forEach((layer, entries) -> layersCopy.put(layer, Map.copyOf(entries)));
        layers = Collections.unmodifiableMap(layersCopy);
        provenance = Map.copyOf(provenance);
    }

    /** 指定来源层当前保存的账本内容（未被保存过的层返回空表，与"已清空"同形）。 */
    public Map<String, String> layerEntries(ConfigLayer layer) {
        return layers.getOrDefault(layer, Map.of());
    }

    /** 指定配置项最终采用的来源层；未登记时返回 null（调用方按内置默认解释）。 */
    public ConfigLayer winningLayerOf(String key) {
        return provenance.get(key);
    }

    /**
     * 按当前配置选中的问候扩展实现；配置指定的 id 缺席时回退到默认实现。
     */
    public GreetingService activeGreeting() {
        GreetingService svc = extensions.get(config.greetingImpl());
        if (svc == null) {
            svc = extensions.values().stream()
                    .filter(s -> s.id().equals("default"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("缺少默认问候扩展"));
        }
        return svc;
    }
}
