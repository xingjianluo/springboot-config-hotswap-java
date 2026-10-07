# springboot-config-hotswap-java

可在运行时热更新的配置与扩展点运行时：应用无需重启即可完成配置与扩展实现的切换，
并发访问下状态始终一致且可解释。

## 1. 配置来源与优先级

配置按层合并，层级声明于 `ConfigLayer`，**高层稳定覆盖低层同键取值**：

| 优先级 | 层 | 说明 |
|---|---|---|
| 低 | `BUILTIN_DEFAULT` | 内置默认值（出厂基线，运行时只读） |
| 中 | `EXTERNAL_FILE` | 外部文件加载的配置 |
| 高 | `RUNTIME_INJECTION` | 运行时注入（如 REST 热更新请求） |

### 分层账本：各来源独立长期保存

三个来源层像三本独立的账本一样长期维护（保存在不可变快照 `RuntimeSnapshot.layers` 中）：

- **一轮更新只替换本次提交的层**，未提交的层保持上一次保存的内容，互不影响；
- 最终生效值**始终**按 `BUILTIN_DEFAULT < EXTERNAL_FILE < RUNTIME_INJECTION` 的固定顺序
  从三本账本重新合并计算，与更新先后无关——先前注入的高优先值不会因为后来更新
  低优先来源而被覆盖；
- 同一层内，本次提交的键值集合整体替换该层旧账本（不是逐键合并）。

### 清空与重置语义

- 对某个来源提交**空内容**（如 `{"RUNTIME_INJECTION": {}}`）表示**显式清空该层**，
  而不是"无变化"；
- 清空高优先层后，最终值立即回退到更低层能提供的取值（例如清空 `RUNTIME_INJECTION`
  后回退到 `EXTERNAL_FILE`，再清空则回退到内置默认）；
- 清空同样要经过整体校验：**如果清空后导致必填缺失或取值非法，本次更新整体不生效**，
  版本号与当前状态（含各层账本）保持原样，失败原因（`MISSING_REQUIRED`/`ILLEGAL_VALUE`）
  与扩展不可用（`EXTENSION_MISSING`/`EXTENSION_INVALID`）等原因明确可区分。

### 内置默认的只读边界

`BUILTIN_DEFAULT` 层是出厂基线，只在启动时建立。**运行时请求不得改写或清空它**：
提交中包含 `BUILTIN_DEFAULT` 层的更新会被整体拒绝，原因码为
`BUILTIN_LAYER_READONLY`，与取值非法、扩展缺失等原因可区分。

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
| 必填字段缺失（含清空导致） | `MISSING_REQUIRED` | 整体拒绝本次更新 |
| 旧版本键名被兼容 | `LEGACY_KEY_ALIAS` | 告警，按别名映射后继续 |
| 运行时改写/清空内置默认层 | `BUILTIN_LAYER_READONLY` | 整体拒绝本次更新 |
| 扩展实现或 id 为空 | `EXTENSION_INVALID` | 整体拒绝本次更新 |
| 配置引用未注册扩展 | `EXTENSION_MISSING` | 整体拒绝本次更新 |

每条问题都携带 `键 @ 来源层` 与说明，拒绝与告警可明确区分；任何取值都不会被静默覆盖或吞掉。

## 2. 版本兼容规则

- 当前模式版本为 **2**（`ConfigAssembler.CURRENT_SCHEMA_VERSION`）。
- v1 使用旧键 `feature.enabled` 表示特性开关；v2 更名为 `feature.x.enabled`。
- 兼容规则：新键缺席时，旧键按别名等价映射（记录 `LEGACY_KEY_ALIAS` 告警），
  **v1 与 v2 配置得出的结论严格一致**（由 `ConfigAssemblerTest.legacyV1KeyIsAcceptedViaAliasWithWarning` 的对照组断言保证）。
- 新旧键**同时出现**视为冲突，以 `ILLEGAL_VALUE` 拒绝——该原因与"缺失"（`MISSING_REQUIRED`）可区分。
- 旧版本配置（v1 键名、v1 取值范围）可继续通过任意可写来源层提交，兼容判定与分层账本
  机制正交：旧键先在其所在层参与合并，再在合并结果上按别名映射。

## 3. 热更新语义

核心组件 `HotSwapRuntime`：

- **原子生效**：每次更新先完整构建不可变快照 `RuntimeSnapshot`（版本号、生效时间、
  分层账本、生效配置、每项来源说明、扩展注册表），再通过 `AtomicReference` 一次性切换。
  并发读取永远看到某一个完整版本，绝不会读到"某一层改了另一层没改"或
  "生效值与来源说明对不上"的中间状态。
- **失败回退**：校验失败、清空导致配置无效、扩展问题或试图改写内置层时，
  旧快照（含各层账本）保持不变，失败原因被记录并可通过状态接口查询，
  不同失败原因互相可区分。
- **更新串行化**：`update` 方法以 monitor 串行化，同一时刻的重复或冲突更新按到达顺序
  逐个生效或失败，版本号严格单调递增，不产生交错结果。
- **扩展点**：`GreetingService` 为扩展点接口，内置 `default`/`formal` 两个实现；
  更新时可按 id 注册/替换实现，配置通过 `greeting.impl` 选择生效实现，切换与配置更新在同一快照内原子完成。

## 4. 状态可观测（REST）

| 端点 | 说明 |
|---|---|
| `GET /runtime/status` | 当前生效版本、生效时间、生效配置、已注册扩展、最近一次失败原因，以及**分层状态**：`layers`（每个来源层当前保存的账本内容）与 `provenance`（每个配置项最终采用的来源层） |
| `GET /runtime/greet?name=X` | 用当前生效快照生成问候语 |
| `POST /runtime/config` | 热更新，请求体如 `{"RUNTIME_INJECTION": {"greeting.impl": "formal"}}`；某层给空对象即清空该层；成功 200，被拒绝 422 且携带 `failureReasons` |

`layers`、`provenance` 与版本号同属一个不可变快照，任何时刻读到的分层解释都与生效值对得上。

## 5. 验证方法

```bash
mvn test
```

| 测试 | 覆盖点 |
|---|---|
| `ConfigAssemblerTest` | 层级优先级、未知键告警、非法值拒绝、必填缺失拒绝、v1 旧键别名兼容（含 v2 对照组）、新旧键冲突拒绝 |
| `HotSwapRuntimeTest` | 失败回滚与可区分失败原因；1 写 8 读并发下快照内字段一致性与版本单调；16 线程重复更新串行化；扩展原子切换 |
| `LayeredStateTest` | 多次交替更新不同来源后优先级仍稳定（高优先注入值不被低优先后续更新覆盖）；清空高优先层后低优先值恢复；清空导致必填缺失时整体回滚且原因与扩展缺失可区分；内置默认层运行时只读（改写/清空均拒绝）；并发读时分层账本/来源说明/生效值三者一致 |
| `RuntimeControllerTest` | REST 端到端：状态查询（含 layers/provenance）、热更新生效、非法更新 422、清空层回退、改写内置层 422 且原因可观测 |
| `ApplicationTests` | Spring 上下文装配 |

所有单测日志均打印**每次输入、当时各来源内容、最终判定和原因**
（`输入=... 判定依据=...` / `输出: ...` / `当前各来源内容: layers=...`），便于审计结论来源。

手动验证：

```bash
mvn spring-boot:run
# 初始分层状态：只有内置层有内容
curl localhost:8080/runtime/status
# 注入层高优先写入
curl -X POST localhost:8080/runtime/config -H 'Content-Type: application/json' \
     -d '{"RUNTIME_INJECTION": {"greeting.impl": "formal", "greeting.prefix": "Welcome"}}'
# 低优先层后更新，不会覆盖上面的高优先注入值
curl -X POST localhost:8080/runtime/config -H 'Content-Type: application/json' \
     -d '{"EXTERNAL_FILE": {"greeting.prefix": "FromFile", "max.connections": "200"}}'
curl localhost:8080/runtime/status   # greeting.prefix 仍为 Welcome，provenance 指向 RUNTIME_INJECTION
# 清空注入层 -> 立即回退到外部文件层的取值
curl -X POST localhost:8080/runtime/config -H 'Content-Type: application/json' \
     -d '{"RUNTIME_INJECTION": {}}'
curl localhost:8080/runtime/status   # greeting.prefix 变为 FromFile
# 尝试改写内置默认层 -> 422，原因 BUILTIN_LAYER_READONLY
curl -X POST localhost:8080/runtime/config -H 'Content-Type: application/json' \
     -d '{"BUILTIN_DEFAULT": {"greeting.prefix": "Hacked"}}'
curl 'localhost:8080/runtime/greet?name=Vincent'
```
