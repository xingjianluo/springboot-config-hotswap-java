package com.github.xingjianluo.springbootconfighotswapjava.config;

/**
 * 合并后的强类型应用配置（不可变）。
 *
 * @param schemaVersion 配置模式版本，用于旧版本兼容判定
 * @param greetingPrefix 问候前缀（必填，无默认值）
 * @param maxConnections 最大连接数，合法范围 [1, 10000]
 * @param featureXEnabled 特性 X 开关
 * @param greetingImpl 生效的问候扩展实现 id
 */
public record AppConfig(
        int schemaVersion,
        String greetingPrefix,
        int maxConnections,
        boolean featureXEnabled,
        String greetingImpl) {

    public static AppConfig empty() {
        return new AppConfig(0, "", 0, false, "");
    }

    /**
     * 导出为键值形式，作为下一次部分更新的基底层，保证未提及的字段沿用当前生效值。
     */
    public java.util.Map<String, String> asMap() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put(ConfigAssembler.KEY_SCHEMA_VERSION, String.valueOf(schemaVersion));
        m.put(ConfigAssembler.KEY_GREETING_PREFIX, greetingPrefix);
        m.put(ConfigAssembler.KEY_MAX_CONNECTIONS, String.valueOf(maxConnections));
        m.put(ConfigAssembler.KEY_FEATURE_X_ENABLED, String.valueOf(featureXEnabled));
        m.put(ConfigAssembler.KEY_GREETING_IMPL, greetingImpl);
        return m;
    }
}
