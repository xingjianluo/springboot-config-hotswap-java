package com.github.xingjianluo.springbootconfighotswapjava.config;

import java.util.EnumMap;
import java.util.Map;

/**
 * 三层配置来源的分层账本（不可变）。
 *
 * <p>每个来源层独立长期维护自己保存的键值内容：一次更新只替换本次提交涉及的层，
 * 未提及的层保持上一次保存的内容不变。最终生效值永远由三层整体按
 * {@link ConfigLayer} 声明的固定优先级（BUILTIN_DEFAULT &lt; EXTERNAL_FILE &lt; RUNTIME_INJECTION）
 * 重新合并得出，高优先层已保存的取值不会因为低优先层后来的更新而丢失。</p>
 *
 * <p>清空语义：以空内容替换某一层即显式清空该层（该层不再提供任何取值），
 * 与"本次更新未提及该层"（保持原内容）严格区分。</p>
 */
public final class LayeredConfigState {

    /** 三层齐全；每层内容均为不可变 map，空 map 表示该层无保存内容。 */
    private final EnumMap<ConfigLayer, Map<String, String>> layers;

    private LayeredConfigState(EnumMap<ConfigLayer, Map<String, String>> layers) {
        this.layers = layers;
    }

    /** 以给定初始内容创建账本；未提供的层按空内容处理。 */
    public static LayeredConfigState of(Map<ConfigLayer, Map<String, String>> initial) {
        EnumMap<ConfigLayer, Map<String, String>> copy = new EnumMap<>(ConfigLayer.class);
        for (ConfigLayer layer : ConfigLayer.values()) {
            Map<String, String> entries = initial.getOrDefault(layer, Map.of());
            copy.put(layer, Map.copyOf(entries));
        }
        return new LayeredConfigState(copy);
    }

    /** 某一层当前保存的内容（只读；空 map 表示该层无内容）。 */
    public Map<String, String> entriesOf(ConfigLayer layer) {
        return layers.get(layer);
    }

    /** 三层完整内容（只读视图），键为层，值为该层保存的键值。 */
    public Map<ConfigLayer, Map<String, String>> layers() {
        return Map.copyOf(layers);
    }

    /**
     * 用给定内容整体替换某一层，返回新账本（本对象不变）。
     * 空内容表示显式清空该层，而不是"无变化"；其余层保持原内容。
     */
    public LayeredConfigState withLayer(ConfigLayer layer, Map<String, String> entries) {
        EnumMap<ConfigLayer, Map<String, String>> copy = new EnumMap<>(layers);
        copy.put(layer, Map.copyOf(entries));
        return new LayeredConfigState(copy);
    }

    /** 显式清空某一层，等价于 {@code withLayer(layer, Map.of())}。 */
    public LayeredConfigState clearLayer(ConfigLayer layer) {
        return withLayer(layer, Map.of());
    }
}
