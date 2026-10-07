package com.github.xingjianluo.springbootconfighotswapjava.web;

import com.github.xingjianluo.springbootconfighotswapjava.config.ConfigAssembler;
import com.github.xingjianluo.springbootconfighotswapjava.config.ConfigLayer;
import com.github.xingjianluo.springbootconfighotswapjava.runtime.HotSwapRuntime;
import com.github.xingjianluo.springbootconfighotswapjava.runtime.RuntimeSnapshot;
import com.github.xingjianluo.springbootconfighotswapjava.runtime.UpdateOutcome;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时状态查询与热更新入口。
 */
@RestController
@RequestMapping("/runtime")
public class RuntimeController {

    private final HotSwapRuntime runtime;

    public RuntimeController(HotSwapRuntime runtime) {
        this.runtime = runtime;
    }

    /** 状态可观测：当前生效版本、生效时间、配置、分层账本、逐项来源、扩展与最近一次失败原因。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        RuntimeSnapshot snap = runtime.current();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", snap.version());
        body.put("effectiveAt", snap.effectiveAt().toString());
        body.put("config", snap.config());
        // 分层账本：每个来源层当前保存的内容（同一快照内读取，彼此对得上）
        Map<String, Object> layers = new LinkedHashMap<>();
        for (ConfigLayer layer : ConfigLayer.values()) {
            layers.put(layer.name(), snap.layers().entriesOf(layer));
        }
        body.put("layers", layers);
        // 逐项来源：每个配置项最终采用了哪个来源层的值（未出现即代码内置默认）
        Map<String, String> sources = new LinkedHashMap<>();
        for (String key : List.of(
                ConfigAssembler.KEY_SCHEMA_VERSION,
                ConfigAssembler.KEY_GREETING_PREFIX,
                ConfigAssembler.KEY_MAX_CONNECTIONS,
                ConfigAssembler.KEY_FEATURE_X_ENABLED,
                ConfigAssembler.KEY_GREETING_IMPL)) {
            ConfigLayer layer = snap.provenance().get(key);
            sources.put(key, layer != null ? layer.name() : "CODE_DEFAULT");
        }
        body.put("effectiveSources", sources);
        body.put("provenance", snap.provenance());
        body.put("extensions", snap.extensions().keySet());
        body.put("lastFailureReason", runtime.lastFailureReason().orElse(null));
        return body;
    }

    /** 使用当前生效配置与扩展生成问候语。 */
    @GetMapping("/greet")
    public Map<String, Object> greet(@RequestParam(defaultValue = "world") String name) {
        RuntimeSnapshot snap = runtime.current();
        return Map.of(
                "version", snap.version(),
                "greeting", snap.config().greetingPrefix() + ", " + snap.activeGreeting().greet(name));
    }

    /**
     * 触发热更新。请求体为层名到键值对的映射，例如：
     * {"RUNTIME_INJECTION": {"greeting.prefix": "Hi", "greeting.impl": "formal"}}
     *
     * <p>语义：出现的层以其内容整体替换该层账本，空对象（如 {"EXTERNAL_FILE": {}}）
     * 表示显式清空该层；未出现的层保持原内容。BUILTIN_DEFAULT 为出厂基线，只读，
     * 提交该层将被拒绝（BUILTIN_LAYER_READONLY）。</p>
     */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> update(@RequestBody Map<String, Map<String, String>> layerBody) {
        Map<ConfigLayer, Map<String, String>> layers = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : layerBody.entrySet()) {
            ConfigLayer layer;
            try {
                layer = ConfigLayer.valueOf(e.getKey());
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "failureReasons", List.of("UNKNOWN_LAYER[" + e.getKey() + "]: 未知的配置来源层")));
            }
            layers.put(layer, e.getValue());
        }
        UpdateOutcome outcome = runtime.update(layers, Map.of());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", outcome.success());
        body.put("version", outcome.version());
        body.put("failureReasons", outcome.failureReasons());
        body.put("warnings", outcome.warnings());
        body.put("effectiveAt", runtime.current().effectiveAt().toString());
        return outcome.success() ? ResponseEntity.ok(body) : ResponseEntity.unprocessableEntity().body(body);
    }
}
