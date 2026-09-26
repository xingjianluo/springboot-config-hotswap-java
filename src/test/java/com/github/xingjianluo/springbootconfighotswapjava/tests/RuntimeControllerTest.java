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
}
