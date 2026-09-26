package com.github.xingjianluo.springbootconfighotswapjava.hotswap.runtime;

import java.time.Instant;

/**
 * 对外可观测的运行时状态。
 */
public record RuntimeStatus(long version,
                            Instant appliedAt,
                            UpdateFailure lastFailure) {
}
