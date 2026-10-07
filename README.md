# springboot-config-hotswap-java

可在运行时热更新的配置与扩展点运行时：应用无需重启即可完成配置与扩展实现的切换，
并发访问下状态始终一致且可解释。

## 1. 配置来源与分层账本

配置按层合并，层级声明于 `ConfigLayer`，**高层稳定覆盖低层同键取值**：

| 优先级 | 层 | 说明 |
|---|---|---|
| 低 | `BUILTIN_DEFAULT` | 内置默认值（出厂基线，运行时只读） |
| 中 | `EXTERNAL_FILE` | 外部文件加载的配置 |
| 高 | `RUNTIME_INJECTION` | 运行时注入（如 REST 热更新请求） |

三个来源层像三本独立的账本一样**长期各自维护**（`LayeredConfigState`，不可变）：

- 一轮更新只**整体替换**本次提交的层，未提及的层保持上一次保存的内容，互不影响；
- 最终生效值永远由三层整体按固定优先级（内置默认 < 外部文件 < 运行时注入）**重新合并**得出，
  高优先层已保存的取值不会因为低优先层后来的更新而被覆盖或丢弃；
- 分层账本、合并后的生效配置、逐项来源说明同属一个不可变快照（`RuntimeSnapshot`），
  并发读取只会看到某一个完整版本，绝不会看到"某一层改了另一层没改"或
  "生效值和来源说明对不上"的中间状态。

### 清空与重置语义

- 请求中某层出现但内容为**空**，表示**显式清空该层**（该层不再提供任何取值），
  与"本次未提及该层"（无变化）严格区分；
- 清空高优先层后，最终值**立即回退**到低优先层保存的取值（都没有则回退到代码内置默认）；
- 清空后如果导致必填缺失（`MISSING_REQUIRED`）或暴露出被掩盖的非法取值（`ILLEGAL_VALUE`），
  **本次更新整体不生效**：版本号、各层账本与生效配置全部保持原样，
  失败原因被记录且与扩展不可用（`EXTENSION_MISSING`）等原因可区分。

### 内置默认的只读边界

`BUILTIN_DEFAULT` 层是出厂基线，只能在代码中设定，**运行时请求不能改写**。
热更新请求一旦包含该层，即以 `BUILTIN_LAYER_READONLY` 明确拒绝（版本与状态不变），
该原因与取值非法、扩展缺失等原因互相可区分。

### 已知配置项

| 键 | 类型 | 约束 | 默认 |
|---|---|---|---|
| `schema.version` | int | [1, 2] | 2（当前版本） |
| `greeting.prefix` | string | 必填，非空 | 出厂基线提供 `Hello` |
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
| 运行时请求改写内置默认层 | `BUILTIN_LAYER_READONLY` | 整体拒绝本次更新 |

每条问题都携带 `键 @ 来源层` 与说明，拒绝与告警可明确区分；任何取值都不会被静默覆盖或吞掉。

## 2. 版本兼容规则

- 当前模式版本为 **2**（`ConfigAssembler.CURRENT_SCHEMA_VERSION`）。
- v1 使用旧键 `feature.enabled` 表示特性开关；v2 更名为 `feature.x.enabled`。
- 兼容规则：新键缺席时，旧键按别名等价映射（记录 `LEGACY_KEY_ALIAS` 告警），
  **v1 与 v2 配置得出的结论严格一致**（由 `ConfigAssemblerTest.legacyV1KeyIsAcceptedViaAliasWithWarning` 的对照组断言保证）。
- 新旧键**同时出现**视为冲突，以 `ILLEGAL_VALUE` 拒绝——该原因与"缺失"（`MISSING_REQUIRED`）可区分。
- 兼容范围：旧版本配置**文件内容**（v1 键名、v1 模式版本号）可原样放入 `EXTERNAL_FILE` 层继续使用；
  分层账本只改变运行时对来源的**维护方式**（按层保存而非压平合并），不改变各层取值参与合并的优先级与校验规则。

## 3. 热更新语义

核心组件 `HotSwapRuntime`：

- **分层账本演进**：`update` 只替换请求中出现的层（空内容=清空该层），
  未出现的层沿用上次保存的内容，然后三层整体重新合并校验；
- **原子生效**：每次更新先完整构建不可变快照 `RuntimeSnapshot`
  （版本号、生效时间、分层账本、生效配置、逐项来源、扩展注册表），
  再通过 `AtomicReference` 一次性切换；
- **失败回退**：校验失败（`ILLEGAL_VALUE`/`MISSING_REQUIRED`/`BUILTIN_LAYER_READONLY`）、
  扩展非法（`EXTENSION_INVALID`）或配置引用未注册扩展（`EXTENSION_MISSING`）时，
  旧快照保持不变，失败原因被记录并可通过状态接口查询，不同失败原因互相可区分；
- **更新串行化**：`update` 方法以 monitor 串行化，同一时刻的重复或冲突更新按到达顺序
  逐个生效或失败，版本号严格单调递增，不产生交错结果；
- **扩展点**：`GreetingService` 为扩展点接口，内置 `default`/`formal` 两个实现；
  更新时可按 id 注册/替换实现，配置通过 `greeting.impl` 选择生效实现，切换与配置更新在同一快照内原子完成。

## 4. 状态可观测（REST）

| 端点 | 说明 |
|---|---|
| `GET /runtime/status` | 当前生效版本、生效时间、生效配置、**各来源层保存的内容（`layers`）**、**每个配置项最终采用的来源层（`effectiveSources`/`provenance`）**、已注册扩展、最近一次失败原因 |
| `GET /runtime/greet?name=X` | 用当前生效快照生成问候语 |
| `POST /runtime/config` | 热更新，请求体如 `{"RUNTIME_INJECTION": {"greeting.impl": "formal"}}`；某层给空对象即清空该层，如 `{"RUNTIME_INJECTION": {}}`；成功 200，被拒绝 422 且携带 `failureReasons` |

状态接口的所有字段取自同一个不可变快照，因此分层内容、生效值与来源说明永远互相对得上。

## 5. 验证方法

```bash
mvn test
```

| 测试 | 覆盖点 |
|---|---|
| `ConfigAssemblerTest` | 层级优先级、未知键告警、非法值拒绝、必填缺失拒绝、v1 旧键别名兼容（含 v2 对照组）、新旧键冲突拒绝 |
| `HotSwapRuntimeTest` | 失败回滚与可区分失败原因；1 写 8 读并发下快照内字段一致性与版本单调；16 线程重复更新串行化；扩展原子切换 |
| `LayeredLedgerTest` | 多次交替更新不同来源后优先级仍稳定；清空高优先层后低优先值恢复（再清空回退到出厂基线）；清空导致必填缺失/取值非法时整体回滚且原因可区分；内置默认层只读；分层场景下 1 写 8 读并发时生效值==三层账本合并结果且来源说明对得上 |
| `RuntimeControllerTest` | REST 端到端：状态查询、热更新生效、非法更新 422 且失败原因可观测；清空语义无空对象请求；`layers`/`effectiveSources` 可观测；改写内置层 422 |
| `ApplicationTests` | Spring 上下文装配 |

所有单测日志均打印**输入、当时各来源内容、最终判定与原因**
（`输入=... 当时各来源=... 判定=...` / `输出: ...`），便于审计结论来源。

手动验证：

```bash
mvn spring-boot:run
# 查看分层状态：各层保存内容、逐项来源、版本与失败原因
curl localhost:8080/runtime/status
# 运行时注入一层配置
curl -X POST localhost:8080/runtime/config -H 'Content-Type: application/json' \
     -d '{"RUNTIME_INJECTION": {"greeting.impl": "formal", "greeting.prefix": "Welcome"}}'
# 再更新外部文件层：运行时注入的高优先值不会被覆盖
curl -X POST localhost:8080/runtime/config -H 'Content-Type: application/json' \
     -d '{"EXTERNAL_FILE": {"greeting.prefix": "FromFile", "max.connections": "50"}}'
curl localhost:8080/runtime/status   # greeting.prefix 仍为 Welcome，来源 RUNTIME_INJECTION
# 清空运行时注入层：立即回退到外部文件层的值
curl -X POST localhost:8080/runtime/config -H 'Content-Type: application/json' \
     -d '{"RUNTIME_INJECTION": {}}'
curl localhost:8080/runtime/status   # greeting.prefix 变为 FromFile，来源 EXTERNAL_FILE
# 尝试改写内置默认层：被 422 拒绝，原因 BUILTIN_LAYER_READONLY
curl -X POST localhost:8080/runtime/config -H 'Content-Type: application/json' \
     -d '{"BUILTIN_DEFAULT": {"greeting.prefix": "Hacked"}}'
curl 'localhost:8080/runtime/greet?name=Vincent'
```
