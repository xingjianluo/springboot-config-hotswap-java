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
}
