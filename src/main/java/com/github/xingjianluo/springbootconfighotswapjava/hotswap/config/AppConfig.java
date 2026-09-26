package com.github.xingjianluo.springbootconfighotswapjava.hotswap.config;

/**
 * 应用运行时配置，不可变。
 *
 * @param schemaVersion     配置结构版本，用于兼容判定
 * @param greeting          问候语模板，非空
 * @param greetingExtension 问候扩展 id，必须指向已注册扩展
 * @param rateLimit         限流阈值，必须在 [1, 100000]
 * @param featureXEnabled   特性开关
 */
public record AppConfig(int schemaVersion,
                        String greeting,
                        String greetingExtension,
                        int rateLimit,
                        boolean featureXEnabled) {

    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final int MIN_SUPPORTED_SCHEMA_VERSION = 1;
}
