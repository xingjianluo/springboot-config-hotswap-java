package com.github.xingjianluo.springbootconfighotswapjava.runtime;

import com.github.xingjianluo.springbootconfighotswapjava.config.AppConfig;
import com.github.xingjianluo.springbootconfighotswapjava.config.ConfigLayer;
import com.github.xingjianluo.springbootconfighotswapjava.config.LayeredConfigState;
import com.github.xingjianluo.springbootconfighotswapjava.ext.GreetingService;

import java.time.Instant;
import java.util.Map;

/**
 * 某一版本的完整运行时状态（不可变）。
 * 读取方通过整体引用切换，只会看到某一个完整版本，绝不会看到半生效的中间状态：
 * 分层账本、合并结果与来源说明始终来自同一次提交，互相对得上。
 *
 * @param layers 三层来源各自保存的内容（分层账本）
 * @param provenance 合并后每个出现过的键最终取值来自哪一层
 */
public record RuntimeSnapshot(
        long version,
        Instant effectiveAt,
        AppConfig config,
        LayeredConfigState layers,
        Map<String, ConfigLayer> provenance,
        Map<String, GreetingService> extensions) {

    public RuntimeSnapshot {
        provenance = Map.copyOf(provenance);
        extensions = Map.copyOf(extensions);
    }

    /**
     * 按当前配置选中的问候扩展实现；配置指定的 id 缺席时回退到默认实现。
     */
    public GreetingService activeGreeting() {
        GreetingService svc = extensions.get(config.greetingImpl());
        if (svc == null) {
            svc = extensions.values().stream()
                    .filter(s -> s.id().equals("default"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("缺少默认问候扩展"));
        }
        return svc;
    }
}
