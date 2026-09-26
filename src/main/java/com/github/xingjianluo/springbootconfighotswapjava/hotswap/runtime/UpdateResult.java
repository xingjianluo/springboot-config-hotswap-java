package com.github.xingjianluo.springbootconfighotswapjava.hotswap.runtime;

import java.util.List;

/**
 * 一次更新请求的结果。
 */
public record UpdateResult(boolean applied, long version, List<String> warnings, UpdateFailure failure) {

    public static UpdateResult ok(long version, List<String> warnings) {
        return new UpdateResult(true, version, List.copyOf(warnings), null);
    }

    public static UpdateResult failed(long currentVersion, UpdateFailure failure) {
        return new UpdateResult(false, currentVersion, List.of(), failure);
    }
}
