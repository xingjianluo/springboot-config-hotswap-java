package com.github.xingjianluo.springbootconfighotswapjava.hotswap.web;

import com.github.xingjianluo.springbootconfighotswapjava.hotswap.config.AppConfig;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.config.ConfigLayer;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.extension.DefaultGreetingExtension;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.extension.GreetingExtension;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.extension.ShoutGreetingExtension;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.runtime.HotswapRuntime;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.runtime.RuntimeState;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.runtime.RuntimeStatus;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.runtime.UpdateResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 热更新运行时 REST 入口。
 */
@RestController
@RequestMapping("/api/runtime")
public class RuntimeController {

    private final HotswapRuntime runtime;

    public RuntimeController(HotswapRuntime runtime) {
        this.runtime = runtime;
    }

    /** 可观测状态：当前版本、生效时间、最近失败原因。 */
    @GetMapping("/status")
    public RuntimeStatus status() {
        return runtime.status();
    }

    /** 当前生效的完整状态快照。 */
    @GetMapping("/state")
    public Map<String, Object> state() {
        RuntimeState s = runtime.currentState();
        return Map.of(
                "version", s.version(),
                "appliedAt", s.appliedAt().toString(),
                "config", s.config(),
                "extensions", s.extensions().extensions().keySet());
    }

    /** 热更新配置：body 为该层的完整键值集合。 */
    @PostMapping("/config")
    public ResponseEntity<UpdateResult> updateConfig(@RequestParam ConfigLayer layer,
                                                     @RequestBody Map<String, String> values) {
        UpdateResult result = runtime.updateConfig(layer, values);
        return result.applied() ? ResponseEntity.ok(result)
                : ResponseEntity.unprocessableEntity().body(result);
    }

    /** 热替换扩展集合：body 为扩展 id 列表（default/shout）。 */
    @PostMapping("/extensions")
    public ResponseEntity<UpdateResult> replaceExtensions(@RequestBody List<String> extensionIds) {
        List<GreetingExtension> extensions = extensionIds.stream()
                .map(RuntimeController::instantiate)
                .toList();
        UpdateResult result = runtime.replaceExtensions(extensions);
        return result.applied() ? ResponseEntity.ok(result)
                : ResponseEntity.unprocessableEntity().body(result);
    }

    /** 使用当前生效版本渲染问候语。 */
    @GetMapping("/greet")
    public Map<String, Object> greet(@RequestParam(defaultValue = "world") String name) {
        RuntimeState s = runtime.currentState();
        AppConfig config = s.config();
        String message = s.extensions().require(config.greetingExtension())
                .greet(name, config.greeting());
        return Map.of("version", s.version(), "message", message);
    }

    private static GreetingExtension instantiate(String id) {
        return switch (id) {
            case DefaultGreetingExtension.ID -> new DefaultGreetingExtension();
            case ShoutGreetingExtension.ID -> new ShoutGreetingExtension();
            default -> throw new IllegalArgumentException("未知扩展 id: " + id);
        };
    }
}
