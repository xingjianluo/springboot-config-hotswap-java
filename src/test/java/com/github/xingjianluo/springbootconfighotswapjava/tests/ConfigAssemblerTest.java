package com.github.xingjianluo.springbootconfighotswapjava.tests;

import com.github.xingjianluo.springbootconfighotswapjava.config.AppConfig;
import com.github.xingjianluo.springbootconfighotswapjava.config.ConfigAssembler;
import com.github.xingjianluo.springbootconfighotswapjava.config.ConfigIssue;
import com.github.xingjianluo.springbootconfighotswapjava.config.ConfigLayer;
import com.github.xingjianluo.springbootconfighotswapjava.config.IssueKind;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分层合并与校验测试：每个用例打印输入与判定依据。
 */
class ConfigAssemblerTest {

    private static final Logger log = LoggerFactory.getLogger(ConfigAssemblerTest.class);

    private final ConfigAssembler assembler = new ConfigAssembler();

    private static Map<ConfigLayer, Map<String, String>> layers(Object... kv) {
        Map<ConfigLayer, Map<String, String>> layers = new EnumMap<>(ConfigLayer.class);
        for (int i = 0; i < kv.length; i += 2) {
            ConfigLayer layer = (ConfigLayer) kv[i];
            @SuppressWarnings("unchecked")
            Map<String, String> entries = (Map<String, String>) kv[i + 1];
            layers.put(layer, entries);
        }
        return layers;
    }

    @Test
    void higherLayerOverridesLowerLayerStably() {
        Map<ConfigLayer, Map<String, String>> input = layers(
                ConfigLayer.BUILTIN_DEFAULT, Map.of("greeting.prefix", "Hello", "max.connections", "100"),
                ConfigLayer.EXTERNAL_FILE, Map.of("max.connections", "200"),
                ConfigLayer.RUNTIME_INJECTION, Map.of("max.connections", "300"));
        log.info("用例=层级优先级 输入={} 判定依据=RUNTIME_INJECTION(300) > EXTERNAL_FILE(200) > BUILTIN_DEFAULT(100)", input);

        ConfigAssembler.AssembleResult result = assembler.assemble(input);

        log.info("输出: config={} issues={}", result.config(), result.issues());
        assertFalse(result.hasFatal());
        assertEquals(300, result.config().maxConnections());
        assertEquals("Hello", result.config().greetingPrefix());
    }

    @Test
    void unknownKeyWarnsAndIsIgnored() {
        Map<ConfigLayer, Map<String, String>> input = layers(
                ConfigLayer.RUNTIME_INJECTION, Map.of("greeting.prefix", "Hi", "no.such.key", "x"));
        log.info("用例=未知配置项 输入={} 判定依据=未知键只告警(UNKNOWN_KEY)且取值不得并入结果", input);

        ConfigAssembler.AssembleResult result = assembler.assemble(input);

        log.info("输出: config={} issues={}", result.config(), result.issues());
        assertFalse(result.hasFatal());
        assertTrue(result.issues().stream().anyMatch(i -> i.kind() == IssueKind.UNKNOWN_KEY
                && i.key().equals("no.such.key")));
        assertEquals("Hi", result.config().greetingPrefix());
    }

    @Test
    void illegalValueIsRejectedWithDistinctReason() {
        Map<ConfigLayer, Map<String, String>> input = layers(
                ConfigLayer.RUNTIME_INJECTION, Map.of("greeting.prefix", "Hi", "max.connections", "99999"));
        log.info("用例=取值非法 输入={} 判定依据=max.connections 超出[1,10000] 必须整体拒绝且原因=ILLEGAL_VALUE", input);

        ConfigAssembler.AssembleResult result = assembler.assemble(input);

        log.info("输出: config={} issues={}", result.config(), result.issues());
        assertTrue(result.hasFatal());
        assertNull(result.config());
        assertTrue(result.issues().stream().anyMatch(i -> i.kind() == IssueKind.ILLEGAL_VALUE
                && i.key().equals("max.connections")));
    }

    @Test
    void missingRequiredFieldIsRejectedWithDistinctReason() {
        Map<ConfigLayer, Map<String, String>> input = layers(
                ConfigLayer.RUNTIME_INJECTION, Map.of("max.connections", "10"));
        log.info("用例=必填缺失 输入={} 判定依据=greeting.prefix 无默认值 必须整体拒绝且原因=MISSING_REQUIRED", input);

        ConfigAssembler.AssembleResult result = assembler.assemble(input);

        log.info("输出: config={} issues={}", result.config(), result.issues());
        assertTrue(result.hasFatal());
        assertTrue(result.issues().stream().anyMatch(i -> i.kind() == IssueKind.MISSING_REQUIRED
                && i.key().equals("greeting.prefix")));
    }

    @Test
    void legacyV1KeyIsAcceptedViaAliasWithWarning() {
        Map<ConfigLayer, Map<String, String>> input = layers(
                ConfigLayer.EXTERNAL_FILE, Map.of(
                        "schema.version", "1",
                        "greeting.prefix", "Hey",
                        "feature.enabled", "true")); // v1 旧键
        log.info("用例=旧版本兼容 输入={} 判定依据=schema.version=1 时旧键 feature.enabled 按别名兼容为 feature.x.enabled，"
                + "结论(开关取值)与新键保持一致并给出 LEGACY_KEY_ALIAS 告警", input);

        ConfigAssembler.AssembleResult result = assembler.assemble(input);

        log.info("输出: config={} issues={}", result.config(), result.issues());
        assertFalse(result.hasFatal());
        AppConfig config = result.config();
        assertEquals(1, config.schemaVersion());
        assertTrue(config.featureXEnabled(), "旧键取值必须等价映射到新字段");
        assertTrue(result.issues().stream().anyMatch(i -> i.kind() == IssueKind.LEGACY_KEY_ALIAS));

        // 对照组：v2 新键得到相同结论
        Map<ConfigLayer, Map<String, String>> v2Input = layers(
                ConfigLayer.EXTERNAL_FILE, Map.of(
                        "schema.version", "2",
                        "greeting.prefix", "Hey",
                        "feature.x.enabled", "true"));
        AppConfig v2 = assembler.assemble(v2Input).config();
        log.info("对照: v2输入={} -> featureXEnabled={} 判定: 与v1结论一致={}", v2Input, v2.featureXEnabled(),
                v2.featureXEnabled() == config.featureXEnabled());
        assertEquals(v2.featureXEnabled(), config.featureXEnabled(), "新旧版本配置的结论必须一致");
    }

    @Test
    void legacyAndNewKeyTogetherIsRejected() {
        Map<ConfigLayer, Map<String, String>> input = layers(
                ConfigLayer.RUNTIME_INJECTION, Map.of(
                        "greeting.prefix", "Hey",
                        "feature.enabled", "true",
                        "feature.x.enabled", "false"));
        log.info("用例=新旧键冲突 输入={} 判定依据=新旧键同时出现无法确定取舍 必须拒绝且原因=ILLEGAL_VALUE(可区分于缺失)", input);

        ConfigAssembler.AssembleResult result = assembler.assemble(input);

        log.info("输出: config={} issues={}", result.config(), result.issues());
        assertTrue(result.hasFatal());
        assertTrue(result.issues().stream().anyMatch(i -> i.kind() == IssueKind.ILLEGAL_VALUE
                && i.key().equals("feature.enabled")));
    }
}
