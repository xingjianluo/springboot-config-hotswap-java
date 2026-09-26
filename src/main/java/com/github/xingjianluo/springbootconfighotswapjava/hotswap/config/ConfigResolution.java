package com.github.xingjianluo.springbootconfighotswapjava.hotswap.config;

import java.util.List;

/**
 * 一次配置合并的结果：要么得到完整配置，要么只有问题列表。
 * 只要存在 REJECT 级问题，config 为 null。
 */
public record ConfigResolution(AppConfig config, List<ConfigIssue> issues) {

    public boolean accepted() {
        return config != null;
    }

    public List<ConfigIssue> rejections() {
        return issues.stream().filter(i -> i.severity() == ConfigIssue.Severity.REJECT).toList();
    }

    public List<ConfigIssue> warnings() {
        return issues.stream().filter(i -> i.severity() == ConfigIssue.Severity.WARN).toList();
    }
}
