# springboot-config-hotswap-java

可在运行时热更新的配置与扩展点运行时：应用无需重启即可完成配置与扩展实现的切换，
并发访问下状态始终一致且可解释。

## 1. 配置来源与优先级

配置按层合并，层级声明于 `ConfigLayer`，**高层稳定覆盖低层同键取值**：

| 优先级 | 层 | 说明 |
|---|---|---|
| 低 | `BUILTIN_DEFAULT` | 内置默认值 |
| 中 | `EXTERNAL_FILE` | 外部文件加载的配置 |
| 高 | `RUNTIME_INJECTION` | 运行时注入（如 REST 热更新请求） |

热更新时，**当前生效配置会作为最底层基底**参与合并：请求只需给出有变化的键，
未提及的字段沿用当前生效值，不会被静默重置。

### 已知配置项

| 键 | 类型 | 约束 | 默认 |
|---|---|---|---|
| `schema.version` | int | [1, 2] | 2（当前版本） |
| `greeting.prefix` | string | 必填，非空 | 无（缺失即拒绝） |
| `max.connections` | int | [1, 10000] | 100 |
| `feature.x.enabled` | boolean | true/false | false |
| `greeting.impl` | string | 必须对应已注册扩展 | `default` |

### 异常处置（可区分、不静默）

| 情形 | 原因码 | 处置 |
|---|---|---|
| 未知配置项 | `UNKNOWN_KEY` | 告警，忽略其取值，不并入结果 |
| 取值非法（越界/无法解析） | `ILLEGAL_VALUE` | 整体拒绝本次更新 |
| 必填字段缺失 | `MISSING_REQUIRED` | 整体拒绝本次更新 |
| 旧版本键名被兼容 | `LEGACY_KEY_ALIAS` | 告警，按别名映射后继续 |

每条问题都携带 `键 @ 来源层` 与说明，拒绝与告警可明确区分；任何取值都不会被静默覆盖或吞掉。

## 2. 版本兼容规则

- 当前模式版本为 **2**（`ConfigAssembler.CURRENT_SCHEMA_VERSION`）。
- v1 使用旧键 `feature.enabled` 表示特性开关；v2 更名为 `feature.x.enabled`。
- 兼容规则：新键缺席时，旧键按别名等价映射（记录 `LEGACY_KEY_ALIAS` 告警），
  **v1 与 v2 配置得出的结论严格一致**（由 `ConfigAssemblerTest.legacyV1KeyIsAcceptedViaAliasWithWarning` 的对照组断言保证）。
- 新旧键**同时出现**视为冲突，以 `ILLEGAL_VALUE` 拒绝——该原因与"缺失"（`MISSING_REQUIRED`）可区分。

## 3. 热更新语义

核心组件 `HotSwapRuntime`：

- **原子生效**：每次更新先完整构建不可变快照 `RuntimeSnapshot`（版本号、生效时间、配置、扩展注册表），
  再通过 `AtomicReference` 一次性切换。并发读取永远看到某一个完整版本，
  绝不会读到只生效一半的中间状态。
- **失败回退**：校验失败（`ILLEGAL_VALUE`/`MISSING_REQUIRED`）、扩展非法（`EXTENSION_INVALID`）
  或配置引用未注册扩展（`EXTENSION_MISSING`）时，旧快照保持不变，
  失败原因被记录并可通过状态接口查询，不同失败原因互相可区分。
- **更新串行化**：`update` 方法以 monitor 串行化，同一时刻的重复或冲突更新按到达顺序
  逐个生效或失败，版本号严格单调递增，不产生交错结果。
- **扩展点**：`GreetingService` 为扩展点接口，内置 `default`/`formal` 两个实现；
  更新时可按 id 注册/替换实现，配置通过 `greeting.impl` 选择生效实现，切换与配置更新在同一快照内原子完成。

## 4. 状态可观测（REST）

| 端点 | 说明 |
|---|---|
| `GET /runtime/status` | 当前生效版本、生效时间、配置、已注册扩展、最近一次失败原因 |
| `GET /runtime/greet?name=X` | 用当前生效快照生成问候语 |
| `POST /runtime/config` | 热更新，请求体如 `{"RUNTIME_INJECTION": {"greeting.impl": "formal"}}`；成功 200，被拒绝 422 且携带 `failureReasons` |

## 5. 验证方法

```bash
mvn test
```

| 测试 | 覆盖点 |
|---|---|
| `ConfigAssemblerTest` | 层级优先级、未知键告警、非法值拒绝、必填缺失拒绝、v1 旧键别名兼容（含 v2 对照组）、新旧键冲突拒绝 |
| `HotSwapRuntimeTest` | 失败回滚与可区分失败原因；1 写 8 读并发下快照内字段一致性（prefix 后缀 == maxConnections）与版本单调；16 线程重复更新串行化（版本恰好 +16）；扩展原子切换 |
| `RuntimeControllerTest` | REST 端到端：状态查询、热更新生效、非法更新 422 且失败原因可观测 |
| `ApplicationTests` | Spring 上下文装配 |

所有单测日志均打印**输入与判定依据**（`输入=... 判定依据=...` / `输出: ...`），便于审计结论来源。

手动验证：

```bash
mvn spring-boot:run
curl localhost:8080/runtime/status
curl -X POST localhost:8080/runtime/config -H 'Content-Type: application/json' \
     -d '{"RUNTIME_INJECTION": {"greeting.impl": "formal", "greeting.prefix": "Welcome"}}'
curl 'localhost:8080/runtime/greet?name=Vincent'
```
