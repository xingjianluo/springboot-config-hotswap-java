package com.github.xingjianluo.springbootconfighotswapjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REST 层端到端验证：状态查询、热更新生效、非法更新被拒绝且原因可观测。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RuntimeControllerTest {

    private static final Logger log = LoggerFactory.getLogger(RuntimeControllerTest.class);

    @Autowired
    private TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    void statusQueryAndHotUpdateRoundTrip() {
        // 1. 查询初始状态
        ResponseEntity<Map> status0 = rest.getForEntity("/runtime/status", Map.class);
        log.info("初始状态: {}", status0.getBody());
        assertEquals(HttpStatus.OK, status0.getStatusCode());
        Number v0 = (Number) status0.getBody().get("version");
        assertNotNull(status0.getBody().get("effectiveAt"));

        // 2. 合法热更新：运行时注入层切换扩展实现
        Map<String, Map<String, String>> req = Map.of(
                "RUNTIME_INJECTION", Map.of("greeting.impl", "formal", "greeting.prefix", "Welcome"));
        log.info("热更新请求: {} 判定依据=更新后 version+1 且 greet 使用 formal 实现", req);
        ResponseEntity<Map> updated = rest.postForEntity("/runtime/config", req, Map.class);
        log.info("热更新响应: {}", updated.getBody());
        assertEquals(HttpStatus.OK, updated.getStatusCode());
        assertEquals(Boolean.TRUE, updated.getBody().get("success"));
        assertEquals(v0.longValue() + 1, ((Number) updated.getBody().get("version")).longValue());

        ResponseEntity<Map> greet = rest.getForEntity("/runtime/greet?name=Vincent", Map.class);
        log.info("更新后问候: {}", greet.getBody());
        assertTrue(((String) greet.getBody().get("greeting")).contains("Dear Vincent"));

        // 3. 非法热更新：取值越界 -> 422，版本不变，失败原因可查询
        Map<String, Map<String, String>> badReq = Map.of(
                "RUNTIME_INJECTION", Map.of("max.connections", "0"));
        log.info("非法更新请求: {} 判定依据=返回422、版本不变、status 中 lastFailureReason 含 ILLEGAL_VALUE", badReq);
        ResponseEntity<Map> rejected = rest.postForEntity("/runtime/config", badReq, Map.class);
        log.info("非法更新响应: {}", rejected.getBody());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatusCode());
        assertEquals(Boolean.FALSE, rejected.getBody().get("success"));

        ResponseEntity<Map> status1 = rest.getForEntity("/runtime/status", Map.class);
        log.info("拒绝后状态: {}", status1.getBody());
        assertEquals(v0.longValue() + 1, ((Number) status1.getBody().get("version")).longValue(), "失败后版本不得前进");
        assertTrue(((String) status1.getBody().get("lastFailureReason")).contains("ILLEGAL_VALUE"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearLayerAndLayeredStatusAreObservable() {
        // 1. 运行时注入一层配置，状态里应能看到该层账本与逐项来源
        Map<String, Map<String, String>> req = Map.of(
                "RUNTIME_INJECTION", Map.of("greeting.prefix", "Layered"));
        ResponseEntity<Map> updated = rest.postForEntity("/runtime/config", req, Map.class);
        log.info("用例=清空与分层状态可观测 输入={} 响应={} 判定依据=status 中 layers/effectiveSources 与生效值对得上",
                req, updated.getBody());
        assertEquals(HttpStatus.OK, updated.getStatusCode());

        ResponseEntity<Map> status1 = rest.getForEntity("/runtime/status", Map.class);
        Map<String, Map<String, String>> layers1 = (Map<String, Map<String, String>>) status1.getBody().get("layers");
        Map<String, String> sources1 = (Map<String, String>) status1.getBody().get("effectiveSources");
        log.info("注入后状态: layers={} effectiveSources={}", layers1, sources1);
        assertEquals("Layered", layers1.get("RUNTIME_INJECTION").get("greeting.prefix"));
        assertEquals("RUNTIME_INJECTION", sources1.get("greeting.prefix"));

        // 2. 空对象=显式清空该层：最终值回退到低优先来源（此处为内置默认）
        Map<String, Map<String, String>> clearReq = Map.of("RUNTIME_INJECTION", Map.of());
        ResponseEntity<Map> cleared = rest.postForEntity("/runtime/config", clearReq, Map.class);
        log.info("清空请求: {} 响应={} 判定依据=prefix 回退为内置默认 Hello 且来源变为 BUILTIN_DEFAULT",
                clearReq, cleared.getBody());
        assertEquals(HttpStatus.OK, cleared.getStatusCode());

        ResponseEntity<Map> status2 = rest.getForEntity("/runtime/status", Map.class);
        Map<String, Map<String, String>> layers2 = (Map<String, Map<String, String>>) status2.getBody().get("layers");
        Map<String, String> sources2 = (Map<String, String>) status2.getBody().get("effectiveSources");
        Map<String, Object> config2 = (Map<String, Object>) status2.getBody().get("config");
        log.info("清空后状态: layers={} effectiveSources={} config={}", layers2, sources2, config2);
        assertTrue(layers2.get("RUNTIME_INJECTION").isEmpty(), "清空后该层账本应为空");
        assertEquals("Hello", config2.get("greetingPrefix"));
        assertEquals("BUILTIN_DEFAULT", sources2.get("greeting.prefix"));

        // 3. 内置默认层只读：提交该层被 422 拒绝，原因可区分
        Map<String, Map<String, String>> builtinReq = Map.of(
                "BUILTIN_DEFAULT", Map.of("greeting.prefix", "Hacked"));
        ResponseEntity<Map> denied = rest.postForEntity("/runtime/config", builtinReq, Map.class);
        log.info("改写内置层请求: {} 响应={} 判定依据=422 且 failureReasons 含 BUILTIN_LAYER_READONLY",
                builtinReq, denied.getBody());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, denied.getStatusCode());
        assertTrue(denied.getBody().get("failureReasons").toString().contains("BUILTIN_LAYER_READONLY"));
    }
}
