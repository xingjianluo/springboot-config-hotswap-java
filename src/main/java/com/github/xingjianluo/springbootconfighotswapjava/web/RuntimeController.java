package com.github.xingjianluo.springbootconfighotswapjava.web;

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

    /**
     * 状态可观测：当前生效版本、生效时间、生效配置、已注册扩展、最近一次失败原因，
     * 以及分层状态——每个来源层当前保存的账本内容（layers）和每个配置项最终采用的
     * 来源层（provenance）。三者与版本号同属一个不可变快照，不会读到中间状态。
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        RuntimeSnapshot snap = runtime.current();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", snap.version());
        body.put("effectiveAt", snap.effectiveAt().toString());
        body.put("config", snap.config());
        body.put("extensions", snap.extensions().keySet());
        body.put("layers", snap.layers());
        body.put("provenance", snap.provenance());
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
     * <p>语义：只替换本次提交的层，其余层保持上次保存的内容；某层提交空对象
     * （如 {"EXTERNAL_FILE": {}}）表示清空该层；BUILTIN_DEFAULT 层为出厂基线，
     * 提交即被拒绝（BUILTIN_LAYER_READONLY）。</p>
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
