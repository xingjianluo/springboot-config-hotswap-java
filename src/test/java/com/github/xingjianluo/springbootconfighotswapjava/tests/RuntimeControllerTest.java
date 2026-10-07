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
    @org.springframework.test.annotation.DirtiesContext(
            methodMode = org.springframework.test.annotation.DirtiesContext.MethodMode.BEFORE_METHOD)
    void layeredStatusClearAndBuiltinReadOnly() {
        // 1. 初始状态即包含分层账本与来源说明
        ResponseEntity<Map> status0 = rest.getForEntity("/runtime/status", Map.class);
        log.info("初始状态: {}", status0.getBody());
        Map<String, Object> layers0 = (Map<String, Object>) status0.getBody().get("layers");
        Map<String, Object> provenance0 = (Map<String, Object>) status0.getBody().get("provenance");
        assertNotNull(layers0, "状态必须暴露各来源层账本");
        assertNotNull(provenance0, "状态必须暴露每个配置项的来源层");
        assertTrue(layers0.containsKey("BUILTIN_DEFAULT"));
        assertEquals("BUILTIN_DEFAULT", provenance0.get("greeting.prefix"));

        // 2. 注入层高优先写入后，来源说明切换
        Map<String, Map<String, String>> req = Map.of(
                "RUNTIME_INJECTION", Map.of("greeting.prefix", "Injected"));
        log.info("热更新请求: {} 判定依据=provenance.greeting.prefix 变为 RUNTIME_INJECTION", req);
        rest.postForEntity("/runtime/config", req, Map.class);
        ResponseEntity<Map> status1 = rest.getForEntity("/runtime/status", Map.class);
        log.info("注入后状态: {}", status1.getBody());
        assertEquals("RUNTIME_INJECTION",
                ((Map<String, Object>) status1.getBody().get("provenance")).get("greeting.prefix"));

        // 3. 清空注入层（空对象）-> 回退内置默认，账本为空
        Map<String, Map<String, String>> clearReq = Map.of("RUNTIME_INJECTION", Map.of());
        log.info("清空请求: {} 判定依据=prefix 回退 Hello 且 RUNTIME_INJECTION 账本为空", clearReq);
        ResponseEntity<Map> cleared = rest.postForEntity("/runtime/config", clearReq, Map.class);
        assertEquals(HttpStatus.OK, cleared.getStatusCode());
        ResponseEntity<Map> status2 = rest.getForEntity("/runtime/status", Map.class);
        log.info("清空后状态: {}", status2.getBody());
        Map<String, Object> layers2 = (Map<String, Object>) status2.getBody().get("layers");
        assertEquals("Hello", ((Map<String, Object>) status2.getBody().get("config")).get("greetingPrefix"));
        assertEquals("BUILTIN_DEFAULT",
                ((Map<String, Object>) status2.getBody().get("provenance")).get("greeting.prefix"));
        assertEquals(Map.of(), layers2.get("RUNTIME_INJECTION"));

        // 4. 尝试改写内置默认层 -> 422 且原因可区分
        Map<String, Map<String, String>> hackReq = Map.of(
                "BUILTIN_DEFAULT", Map.of("greeting.prefix", "Hacked"));
        log.info("改写内置层请求: {} 判定依据=422 且 failureReasons 含 BUILTIN_LAYER_READONLY", hackReq);
        ResponseEntity<Map> rejected = rest.postForEntity("/runtime/config", hackReq, Map.class);
        log.info("改写内置层响应: {}", rejected.getBody());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatusCode());
        assertTrue(rejected.getBody().get("failureReasons").toString().contains("BUILTIN_LAYER_READONLY"));
        ResponseEntity<Map> status3 = rest.getForEntity("/runtime/status", Map.class);
        assertEquals(((Number) status2.getBody().get("version")).longValue(),
                ((Number) status3.getBody().get("version")).longValue(), "被拒绝后版本不得前进");
    }
}
