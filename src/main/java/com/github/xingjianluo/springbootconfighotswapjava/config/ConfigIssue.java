package com.github.xingjianluo.springbootconfighotswapjava.config;

/**
 * 一条配置问题记录：键、来源层、可区分原因与说明。
 */
public record ConfigIssue(String key, ConfigLayer layer, IssueKind kind, String message) {

    public boolean isFatal() {
        return kind == IssueKind.ILLEGAL_VALUE || kind == IssueKind.MISSING_REQUIRED
                || kind == IssueKind.BUILTIN_LAYER_READONLY;
    }
}
