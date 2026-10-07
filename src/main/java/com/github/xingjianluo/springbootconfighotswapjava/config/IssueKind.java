package com.github.xingjianluo.springbootconfighotswapjava.config;

/**
 * 配置问题的可区分原因。
 */
public enum IssueKind {
    /** 未知的配置项（告警，不拒绝） */
    UNKNOWN_KEY,
    /** 取值非法（拒绝） */
    ILLEGAL_VALUE,
    /** 必填字段缺失（拒绝） */
    MISSING_REQUIRED,
    /** 旧版本键名被别名兼容（告警） */
    LEGACY_KEY_ALIAS,
    /** 试图通过运行时请求改写内置默认层（拒绝：内置默认是出厂基线，只读） */
    BUILTIN_LAYER_READONLY
}
