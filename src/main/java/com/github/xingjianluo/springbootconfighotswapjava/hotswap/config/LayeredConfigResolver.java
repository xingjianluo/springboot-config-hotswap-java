package com.github.xingjianluo.springbootconfighotswapjava.hotswap.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分层配置合并器：按 {@link ConfigLayer} 优先级合并，校验并产出 {@link ConfigResolution}。
 *
 * <p>处置规则：
 * <ul>
 *   <li>同一键高层覆盖低层（BUILTIN_DEFAULT &lt; EXTERNAL_FILE &lt; RUNTIME_INJECTED）；</li>
 *   <li>未知键：WARN/UNKNOWN_KEY，忽略该键，绝不静默并入；</li>
 *   <li>已知键缺失：WARN/MISSING_FIELD，回退到内置默认值；</li>
 *   <li>已知键取值非法（含不受支持的 schemaVersion）：REJECT/ILLEGAL_VALUE，整体拒绝。</li>
 * </ul>
 */
public class LayeredConfigResolver {

    public static final String KEY_SCHEMA_VERSION = "schemaVersion";
    public static final String KEY_GREETING = "greeting";
    public static final String KEY_GREETING_EXTENSION = "greetingExtension";
    public static final String KEY_RATE_LIMIT = "rateLimit";
    public static final String KEY_FEATURE_X = "featureXEnabled";

    private static final Set<String> KNOWN_KEYS = Set.of(
            KEY_SCHEMA_VERSION, KEY_GREETING, KEY_GREETING_EXTENSION, KEY_RATE_LIMIT, KEY_FEATURE_X);

    private static final String DEFAULT_GREETING = "Hello";
    private static final String DEFAULT_GREETING_EXTENSION = "default";
    private static final int DEFAULT_RATE_LIMIT = 100;
    private static final boolean DEFAULT_FEATURE_X = false;

    private static final int RATE_LIMIT_MIN = 1;
    private static final int RATE_LIMIT_MAX = 100_000;

    public ConfigResolution resolve(Map<ConfigLayer, Map<String, String>> layers) {
        List<ConfigIssue> issues = new ArrayList<>();

        // 1. 按优先级从低到高合并，高层覆盖低层；记录每个生效键的来源层。
        Map<String, String> merged = new LinkedHashMap<>();
        Map<String, ConfigLayer> provenance = new LinkedHashMap<>();
        layers.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> e.getKey().priority()))
                .forEach(e -> e.getValue().forEach((k, v) -> {
                    merged.put(k, v);
                    provenance.put(k, e.getKey());
                }));

        // 2. 未知键：告警并忽略。
        for (String key : merged.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                issues.add(new ConfigIssue(ConfigIssue.Kind.UNKNOWN_KEY,
                        ConfigIssue.Severity.WARN, key,
                        "未知配置项，来自 " + provenance.get(key) + "，已忽略"));
            }
        }

        // 3. 逐字段解析；缺失回退默认并告警，非法取值拒绝。
        Integer schemaVersion = parseInt(merged, provenance, issues, KEY_SCHEMA_VERSION,
                AppConfig.CURRENT_SCHEMA_VERSION);
        if (schemaVersion != null
                && (schemaVersion < AppConfig.MIN_SUPPORTED_SCHEMA_VERSION
                    || schemaVersion > AppConfig.CURRENT_SCHEMA_VERSION)) {
            issues.add(new ConfigIssue(ConfigIssue.Kind.ILLEGAL_VALUE,
                    ConfigIssue.Severity.REJECT, KEY_SCHEMA_VERSION,
                    "不支持的配置结构版本 " + schemaVersion + "，支持范围 ["
                            + AppConfig.MIN_SUPPORTED_SCHEMA_VERSION + ", "
                            + AppConfig.CURRENT_SCHEMA_VERSION + "]，来自 " + provenance.get(KEY_SCHEMA_VERSION)));
            schemaVersion = null;
        }

        String greeting = merged.get(KEY_GREETING);
        if (greeting == null) {
            issues.add(missing(KEY_GREETING, DEFAULT_GREETING));
            greeting = DEFAULT_GREETING;
        } else if (greeting.isBlank()) {
            issues.add(new ConfigIssue(ConfigIssue.Kind.ILLEGAL_VALUE,
                    ConfigIssue.Severity.REJECT, KEY_GREETING,
                    "问候语模板不能为空，来自 " + provenance.get(KEY_GREETING)));
            greeting = null;
        }

        String greetingExtension = merged.get(KEY_GREETING_EXTENSION);
        if (greetingExtension == null) {
            issues.add(missing(KEY_GREETING_EXTENSION, DEFAULT_GREETING_EXTENSION));
            greetingExtension = DEFAULT_GREETING_EXTENSION;
        } else if (greetingExtension.isBlank()) {
            issues.add(new ConfigIssue(ConfigIssue.Kind.ILLEGAL_VALUE,
                    ConfigIssue.Severity.REJECT, KEY_GREETING_EXTENSION,
                    "扩展 id 不能为空，来自 " + provenance.get(KEY_GREETING_EXTENSION)));
            greetingExtension = null;
        }

        Integer rateLimit = parseInt(merged, provenance, issues, KEY_RATE_LIMIT, DEFAULT_RATE_LIMIT);
        if (rateLimit != null && (rateLimit < RATE_LIMIT_MIN || rateLimit > RATE_LIMIT_MAX)) {
            issues.add(new ConfigIssue(ConfigIssue.Kind.ILLEGAL_VALUE,
                    ConfigIssue.Severity.REJECT, KEY_RATE_LIMIT,
                    "限流阈值 " + rateLimit + " 超出范围 [" + RATE_LIMIT_MIN + ", " + RATE_LIMIT_MAX
                            + "]，来自 " + provenance.get(KEY_RATE_LIMIT)));
            rateLimit = null;
        }

        Boolean featureX = parseBoolean(merged, provenance, issues, KEY_FEATURE_X, DEFAULT_FEATURE_X);

        // 4. 存在任何 REJECT 则整体拒绝，不产出半成品配置。
        boolean rejected = issues.stream().anyMatch(i -> i.severity() == ConfigIssue.Severity.REJECT);
        if (rejected || schemaVersion == null || greeting == null || greetingExtension == null
                || rateLimit == null || featureX == null) {
            return new ConfigResolution(null, List.copyOf(issues));
        }
        return new ConfigResolution(
                new AppConfig(schemaVersion, greeting, greetingExtension, rateLimit, featureX),
                List.copyOf(issues));
    }

    private ConfigIssue missing(String key, Object defaultValue) {
        return new ConfigIssue(ConfigIssue.Kind.MISSING_FIELD,
                ConfigIssue.Severity.WARN, key,
                "字段缺失，回退内置默认值 " + defaultValue);
    }

    private Integer parseInt(Map<String, String> merged, Map<String, ConfigLayer> provenance,
                             List<ConfigIssue> issues, String key, int defaultValue) {
        String raw = merged.get(key);
        if (raw == null) {
            issues.add(missing(key, defaultValue));
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            issues.add(new ConfigIssue(ConfigIssue.Kind.ILLEGAL_VALUE,
                    ConfigIssue.Severity.REJECT, key,
                    "无法解析为整数: '" + raw + "'，来自 " + provenance.get(key)));
            return null;
        }
    }

    private Boolean parseBoolean(Map<String, String> merged, Map<String, ConfigLayer> provenance,
                                 List<ConfigIssue> issues, String key, boolean defaultValue) {
        String raw = merged.get(key);
        if (raw == null) {
            issues.add(missing(key, defaultValue));
            return defaultValue;
        }
        String v = raw.trim().toLowerCase();
        if (v.equals("true") || v.equals("false")) {
            return Boolean.parseBoolean(v);
        }
        issues.add(new ConfigIssue(ConfigIssue.Kind.ILLEGAL_VALUE,
                ConfigIssue.Severity.REJECT, key,
                "无法解析为布尔值(true/false): '" + raw + "'，来自 " + provenance.get(key)));
        return null;
    }
}
