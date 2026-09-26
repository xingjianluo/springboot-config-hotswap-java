package com.github.xingjianluo.springbootconfighotswapjava.config;

/**
 * 配置来源层级，ordinal 越大优先级越高。
 */
public enum ConfigLayer {
    BUILTIN_DEFAULT,
    EXTERNAL_FILE,
    RUNTIME_INJECTION
}
