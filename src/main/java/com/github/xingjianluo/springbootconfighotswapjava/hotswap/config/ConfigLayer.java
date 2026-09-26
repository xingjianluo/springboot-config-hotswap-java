package com.github.xingjianluo.springbootconfighotswapjava.hotswap.config;

/**
 * 配置来源层级，数值越大优先级越高。
 */
public enum ConfigLayer {
    BUILTIN_DEFAULT(0),
    EXTERNAL_FILE(1),
    RUNTIME_INJECTED(2);

    private final int priority;

    ConfigLayer(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
