package com.github.xingjianluo.springbootconfighotswapjava.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分层配置合并器。
 *
 * <p>合并规则：按 {@link ConfigLayer} 声明顺序（BUILTIN_DEFAULT &lt; EXTERNAL_FILE &lt; RUNTIME_INJECTION）
 * 逐层覆盖，高层取值稳定地覆盖低层同键取值。</p>
 *
 * <p>处置规则：</p>
 * <ul>
 *   <li>未知配置项：记录 {@link IssueKind#UNKNOWN_KEY} 告警，忽略其取值，绝不静默并入；</li>
 *   <li>取值非法：记录 {@link IssueKind#ILLEGAL_VALUE} 拒绝原因，整体拒绝本次合并；</li>
 *   <li>必填字段缺失：记录 {@link IssueKind#MISSING_REQUIRED} 拒绝原因，整体拒绝；</li>
 *   <li>旧版本键名（v1 的 {@code feature.enabled}）：在新键缺席时按别名兼容，
 *       记录 {@link IssueKind#LEGACY_KEY_ALIAS} 告警；新旧键同时出现视为非法，拒绝。</li>
 * </ul>
 */
public class ConfigAssembler {

    /** 当前配置模式版本 */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public static final String KEY_SCHEMA_VERSION = "schema.version";
    public static final String KEY_GREETING_PREFIX = "greeting.prefix";
    public static final String KEY_MAX_CONNECTIONS = "max.connections";
    public static final String KEY_FEATURE_X_ENABLED = "feature.x.enabled";
    public static final String KEY_GREETING_IMPL = "greeting.impl";
    /** v1 旧键：特性开关 */
    public static final String LEGACY_KEY_FEATURE_ENABLED = "feature.enabled";

    private static final Set<String> KNOWN_KEYS = Set.of(
            KEY_SCHEMA_VERSION, KEY_GREETING_PREFIX, KEY_MAX_CONNECTIONS,
            KEY_FEATURE_X_ENABLED, KEY_GREETING_IMPL, LEGACY_KEY_FEATURE_ENABLED);

    private static final int MIN_MAX_CONNECTIONS = 1;
    private static final int MAX_MAX_CONNECTIONS = 10000;

    /**
     * 合并结果：存在致命问题时 {@code config} 为 null；问题列表始终完整给出。
     *
     * @param provenance 每个出现过的原始键最终采用的来源层（高层覆盖低层后的胜者），
     *                   用于状态查询解释"该键的值来自哪一层"
     */
    public record AssembleResult(AppConfig config, List<ConfigIssue> issues,
                                 Map<String, ConfigLayer> provenance) {
        public AssembleResult {
            provenance = Map.copyOf(provenance);
        }

        public boolean hasFatal() {
            return issues.stream().anyMatch(ConfigIssue::isFatal);
        }
    }

    public AssembleResult assemble(Map<ConfigLayer, Map<String, String>> layers) {
        List<ConfigIssue> issues = new ArrayList<>();

        // 1. 按优先级逐层合并，并记录每个键的最终来源层
        Map<String, String> merged = new LinkedHashMap<>();
        Map<String, ConfigLayer> provenance = new LinkedHashMap<>();
        for (ConfigLayer layer : ConfigLayer.values()) {
            Map<String, String> entries = layers.getOrDefault(layer, Map.of());
            for (Map.Entry<String, String> e : entries.entrySet()) {
                merged.put(e.getKey(), e.getValue());
                provenance.put(e.getKey(), layer);
            }
        }

        // 2. 未知键：告警并忽略
        for (Map.Entry<String, String> e : merged.entrySet()) {
            if (!KNOWN_KEYS.contains(e.getKey())) {
                issues.add(new ConfigIssue(e.getKey(), provenance.get(e.getKey()),
                        IssueKind.UNKNOWN_KEY, "未知配置项，已忽略其取值: " + e.getValue()));
            }
        }

        // 3. 旧版本键别名兼容：新键缺席时沿用旧键；新旧并存视为冲突，拒绝
        String featureRaw = merged.get(KEY_FEATURE_X_ENABLED);
        String legacyRaw = merged.get(LEGACY_KEY_FEATURE_ENABLED);
        if (legacyRaw != null) {
            if (featureRaw != null) {
                issues.add(new ConfigIssue(LEGACY_KEY_FEATURE_ENABLED, provenance.get(LEGACY_KEY_FEATURE_ENABLED),
                        IssueKind.ILLEGAL_VALUE,
                        "旧键 " + LEGACY_KEY_FEATURE_ENABLED + " 与新键 " + KEY_FEATURE_X_ENABLED + " 同时出现，无法确定取舍"));
            } else {
                featureRaw = legacyRaw;
                issues.add(new ConfigIssue(LEGACY_KEY_FEATURE_ENABLED, provenance.get(LEGACY_KEY_FEATURE_ENABLED),
                        IssueKind.LEGACY_KEY_ALIAS,
                        "旧版本键名，已按别名兼容为 " + KEY_FEATURE_X_ENABLED));
            }
        }

        // 4. 逐字段解析与校验
        Integer schemaVersion = parseInt(merged, provenance, issues, KEY_SCHEMA_VERSION, 1, CURRENT_SCHEMA_VERSION);
        if (schemaVersion == null) {
            schemaVersion = CURRENT_SCHEMA_VERSION; // 缺省视为当前版本
        }

        String greetingPrefix = merged.get(KEY_GREETING_PREFIX);
        if (greetingPrefix == null || greetingPrefix.isBlank()) {
            issues.add(new ConfigIssue(KEY_GREETING_PREFIX, provenance.get(KEY_GREETING_PREFIX),
                    IssueKind.MISSING_REQUIRED, "必填字段缺失或为空"));
        }

        Integer maxConnections = parseInt(merged, provenance, issues, KEY_MAX_CONNECTIONS,
                MIN_MAX_CONNECTIONS, MAX_MAX_CONNECTIONS);
        if (maxConnections == null && !merged.containsKey(KEY_MAX_CONNECTIONS)) {
            maxConnections = 100; // 内置默认
        }

        Boolean featureX = parseBoolean(featureRaw, KEY_FEATURE_X_ENABLED, provenance, merged, issues);
        if (featureX == null && featureRaw == null) {
            featureX = Boolean.FALSE; // 内置默认
        }

        String greetingImpl = merged.getOrDefault(KEY_GREETING_IMPL, DefaultValues.GREETING_IMPL);
        if (greetingImpl.isBlank()) {
            issues.add(new ConfigIssue(KEY_GREETING_IMPL, provenance.get(KEY_GREETING_IMPL),
                    IssueKind.ILLEGAL_VALUE, "扩展实现 id 不能为空"));
        }

        // 5. 存在致命问题则整体拒绝
        boolean fatal = issues.stream().anyMatch(ConfigIssue::isFatal);
        if (fatal) {
            return new AssembleResult(null, List.copyOf(issues), provenance);
        }
        AppConfig config = new AppConfig(schemaVersion, greetingPrefix, maxConnections,
                featureX != null && featureX, greetingImpl);
        return new AssembleResult(config, List.copyOf(issues), provenance);
    }

    private Integer parseInt(Map<String, String> merged, Map<String, ConfigLayer> provenance,
                             List<ConfigIssue> issues, String key, int min, int max) {
        String raw = merged.get(key);
        if (raw == null) {
            return null;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < min || v > max) {
                issues.add(new ConfigIssue(key, provenance.get(key), IssueKind.ILLEGAL_VALUE,
                        "取值 " + v + " 超出合法范围 [" + min + ", " + max + "]"));
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            issues.add(new ConfigIssue(key, provenance.get(key), IssueKind.ILLEGAL_VALUE,
                    "无法解析为整数: " + raw));
            return null;
        }
    }

    private Boolean parseBoolean(String raw, String key, Map<String, ConfigLayer> provenance,
                                 Map<String, String> merged, List<ConfigIssue> issues) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase();
        if (v.equals("true") || v.equals("false")) {
            return Boolean.valueOf(v);
        }
        ConfigLayer layer = provenance.getOrDefault(key, provenance.get(LEGACY_KEY_FEATURE_ENABLED));
        issues.add(new ConfigIssue(key, layer, IssueKind.ILLEGAL_VALUE,
                "无法解析为布尔值(true/false): " + raw));
        return null;
    }

    /** 内置默认值集中声明处 */
    private static final class DefaultValues {
        static final String GREETING_IMPL = "default";
    }
}
