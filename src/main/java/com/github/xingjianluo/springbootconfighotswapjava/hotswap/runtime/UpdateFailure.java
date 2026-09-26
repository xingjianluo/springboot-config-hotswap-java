package com.github.xingjianluo.springbootconfighotswapjava.hotswap.runtime;

import java.time.Instant;
import java.util.List;

/**
 * 一次失败更新的记录，原因可区分。
 */
public record UpdateFailure(Reason reason, List<String> details, Instant occurredAt) {

    public enum Reason {
        CONFIG_REJECTED,
        UNKNOWN_EXTENSION,
        EXTENSION_CONFLICT,
        INTERNAL_ERROR
    }
}
