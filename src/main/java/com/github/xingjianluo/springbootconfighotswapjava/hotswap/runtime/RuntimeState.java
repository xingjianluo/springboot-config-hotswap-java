package com.github.xingjianluo.springbootconfighotswapjava.hotswap.runtime;

import com.github.xingjianluo.springbootconfighotswapjava.hotswap.config.AppConfig;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.extension.ExtensionSnapshot;

import java.time.Instant;

/**
 * 运行时状态的一个完整版本：配置与扩展快照绑定在一起，整体原子替换。
 */
public record RuntimeState(long version,
                           AppConfig config,
                           ExtensionSnapshot extensions,
                           Instant appliedAt) {
}
