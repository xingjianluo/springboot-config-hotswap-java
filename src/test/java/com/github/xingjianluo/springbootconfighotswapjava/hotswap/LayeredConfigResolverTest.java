package com.github.xingjianluo.springbootconfighotswapjava.hotswap;

import com.github.xingjianluo.springbootconfighotswapjava.hotswap.config.AppConfig;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.config.ConfigIssue;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.config.ConfigLayer;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.config.ConfigResolution;
import com.github.xingjianluo.springbootconfighotswapjava.hotswap.config.LayeredConfigResolver;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分层配置合并与校验测试。每个用例打印输入与判定依据。
 */
class LayeredConfigResolverTest {

    private static final Logger log = LoggerFactory.getLogger(LayeredConfigResolverTest.class);

    private final LayeredConfigResolver resolver = new LayeredConfigResolver();

    private static Map<ConfigLayer, Map<String, String>> layers() {
        Map<ConfigLayer, Map<String, String>> m = new EnumMap<>(ConfigLayer.class);
        m.put(ConfigLayer.BUILTIN_DEFAULT, Map.of(
                "schemaVersion", "2",
                "greeting", "Hello",
                "greetingExtension", "default",
                "rateLimit", "100",
                "featureXEnabled", "false"));
        return m;
    }

    private static void logCase(String name, Object input, ConfigResolution result, String basis) {
        log.info("[用例:{}] 输入={} | 判定依据={} | 结果: accepted={}, issues={}",
                name, input, basis, result.accepted(), result.issues());
    }

    @Test
    void higherLayerOverridesLowerLayer() {
        Map<ConfigLayer, Map<String, String>> input = layers();
        input.put(ConfigLayer.EXTERNAL_FILE, Map.of("rateLimit", "500"));
        input.put(ConfigLayer.RUNTIME_INJECTED, Map.of("rateLimit", "9000", "greeting", "Hi"));

        ConfigResolution r = resolver.resolve(input);
        logCase("优先级合并", input, r,
                "RUNTIME_INJECTED(2) > EXTERNAL_FILE(1) > BUILTIN_DEFAULT(0)，同键高层覆盖低层");

        assertTrue(r.accepted());
        assertEquals(9000, r.config().rateLimit());
        assertEquals("Hi", r.config().greeting());
        assertEquals("default", r.config().greetingExtension());
    }

    @Test
    void missingFieldFallsBackWithWarning() {
        Map<ConfigLayer, Map<String, String>> input = layers();
        input.put(ConfigLayer.EXTERNAL_FILE, Map.of("rateLimit", "500"));
        // 内置默认层缺失 greeting
        input.put(ConfigLayer.BUILTIN_DEFAULT, Map.of(
                "schemaVersion", "2", "greetingExtension", "default",
                "rateLimit", "100", "featureXEnabled", "false"));

        ConfigResolution r = resolver.resolve(input);
        logCase("字段缺失", input, r, "缺失字段回退内置默认值并产生 WARN/MISSING_FIELD");

        assertTrue(r.accepted());
        assertEquals("Hello", r.config().greeting());
        assertTrue(r.warnings().stream().anyMatch(i ->
                i.kind() == ConfigIssue.Kind.MISSING_FIELD && i.key().equals("greeting")));
    }

    @Test
    void illegalValueIsRejected() {
        Map<ConfigLayer, Map<String, String>> input = layers();
        input.put(ConfigLayer.RUNTIME_INJECTED, Map.of("rateLimit", "not-a-number"));

        ConfigResolution r = resolver.resolve(input);
        logCase("非法取值", input, r, "rateLimit 无法解析为整数 -> REJECT/ILLEGAL_VALUE，整体拒绝");

        assertFalse(r.accepted());
        assertTrue(r.rejections().stream().anyMatch(i ->
                i.kind() == ConfigIssue.Kind.ILLEGAL_VALUE && i.key().equals("rateLimit")));
    }

    @Test
    void outOfRangeValueIsRejected() {
        Map<ConfigLayer, Map<String, String>> input = layers();
        input.put(ConfigLayer.RUNTIME_INJECTED, Map.of("rateLimit", "0"));

        ConfigResolution r = resolver.resolve(input);
        logCase("越界取值", input, r, "rateLimit=0 超出 [1,100000] -> REJECT/ILLEGAL_VALUE");

        assertFalse(r.accepted());
        assertTrue(r.rejections().stream().anyMatch(i -> i.key().equals("rateLimit")));
    }

    @Test
    void unknownKeyWarnsAndIsIgnored() {
        Map<ConfigLayer, Map<String, String>> input = layers();
        input.put(ConfigLayer.RUNTIME_INJECTED, Map.of("noSuchKey", "x", "rateLimit", "42"));

        ConfigResolution r = resolver.resolve(input);
        logCase("未知配置项", input, r, "未知键产生 WARN/UNKNOWN_KEY 并被忽略，不影响已知键合并");

        assertTrue(r.accepted());
        assertEquals(42, r.config().rateLimit());
        assertTrue(r.warnings().stream().anyMatch(i ->
                i.kind() == ConfigIssue.Kind.UNKNOWN_KEY && i.key().equals("noSuchKey")));
    }

    @Test
    void oldSchemaVersionRemainsCompatible() {
        Map<ConfigLayer, Map<String, String>> input = layers();
        input.put(ConfigLayer.EXTERNAL_FILE, Map.of("schemaVersion", "1", "greeting", "OldHi"));

        ConfigResolution r = resolver.resolve(input);
        logCase("旧版本兼容", input, r,
                "schemaVersion=1 在支持范围 [" + AppConfig.MIN_SUPPORTED_SCHEMA_VERSION
                        + "," + AppConfig.CURRENT_SCHEMA_VERSION + "] 内，按旧版本语义接受");

        assertTrue(r.accepted());
        assertEquals(1, r.config().schemaVersion());
        assertEquals("OldHi", r.config().greeting());
    }

    @Test
    void unsupportedSchemaVersionIsRejected() {
        Map<ConfigLayer, Map<String, String>> input = layers();
        input.put(ConfigLayer.RUNTIME_INJECTED, Map.of("schemaVersion", "99"));

        ConfigResolution r = resolver.resolve(input);
        logCase("不支持的结构版本", input, r,
                "schemaVersion=99 超出支持范围 -> REJECT/ILLEGAL_VALUE，原因可区分");

        assertFalse(r.accepted());
        assertTrue(r.rejections().stream().anyMatch(i ->
                i.kind() == ConfigIssue.Kind.ILLEGAL_VALUE && i.key().equals("schemaVersion")
                        && i.detail().contains("不支持的配置结构版本")));
    }
}
