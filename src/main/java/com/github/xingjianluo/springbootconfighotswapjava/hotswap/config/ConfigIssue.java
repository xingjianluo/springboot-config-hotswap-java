package com.github.xingjianluo.springbootconfighotswapjava.hotswap.config;

/**
 * 配置合并/校验过程中发现的问题，类型可区分。
 */
public record ConfigIssue(Kind kind, Severity severity, String key, String detail) {

    public enum Kind {
        MISSING_FIELD,
        ILLEGAL_VALUE,
        UNKNOWN_KEY
    }

    public enum Severity {
        REJECT,
        WARN
    }

    @Override
    public String toString() {
        return severity + "/" + kind + "[" + key + "]: " + detail;
    }
}
