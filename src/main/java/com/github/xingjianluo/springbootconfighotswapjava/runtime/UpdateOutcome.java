package com.github.xingjianluo.springbootconfighotswapjava.runtime;

import java.util.List;

/**
 * 一次热更新的结果：成功/失败、生效版本与可区分的失败原因。
 */
public record UpdateOutcome(
        boolean success,
        long version,
        List<String> failureReasons,
        List<String> warnings) {

    public static UpdateOutcome ok(long version, List<String> warnings) {
        return new UpdateOutcome(true, version, List.of(), warnings);
    }

    public static UpdateOutcome failed(long currentVersion, List<String> reasons, List<String> warnings) {
        return new UpdateOutcome(false, currentVersion, List.copyOf(reasons), List.copyOf(warnings));
    }
}
