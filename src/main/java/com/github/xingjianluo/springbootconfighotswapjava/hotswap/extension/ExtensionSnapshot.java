package com.github.xingjianluo.springbootconfighotswapjava.hotswap.extension;

import java.util.Map;

/**
 * 扩展注册表的不可变快照：任意时刻读到的都是一个完整版本。
 */
public record ExtensionSnapshot(Map<String, GreetingExtension> extensions) {

    public ExtensionSnapshot {
        extensions = Map.copyOf(extensions);
    }

    public boolean contains(String id) {
        return extensions.containsKey(id);
    }

    public GreetingExtension require(String id) {
        GreetingExtension ext = extensions.get(id);
        if (ext == null) {
            throw new IllegalStateException("扩展不存在: " + id + "，可用: " + extensions.keySet());
        }
        return ext;
    }
}
