# Spring Boot 配置与扩展点热更新运行时

本工程实现了一套可在运行时热更新的配置与扩展点运行时：应用无需重启即可完成
配置与扩展实现的切换，并保证并发访问下状态始终一致且可解释。

## 模块结构

```
hotswap/
├── config/    分层配置合并与校验
│   ├── ConfigLayer              配置来源层级（优先级）
│   ├── LayeredConfigResolver    合并 + 校验，产出 ConfigResolution
│   ├── AppConfig                不可变配置（含 schemaVersion 兼容字段）
│   └── ConfigIssue              可区分的问题（Kind × Severity）
├── extension/ 扩展点 SPI 与不可变快照
│   ├── GreetingExtension        扩展点接口
│   ├── Default/ShoutGreetingExtension  内置实现
│   └── ExtensionSnapshot        扩展注册表的不可变快照
├── runtime/   热更新核心
│   ├── HotswapRuntime           串行化更新、原子生效、失败回退
│   ├── RuntimeState             一个完整版本（配置 + 扩展快照 + 版本 + 生效时间）
│   ├── RuntimeStatus            可观测状态（版本 / 生效时间 / 最近失败原因）
│   └── UpdateFailure            失败记录（原因可区分）
└── web/       RuntimeController REST 入口
```

## 配置来源优先级

三层来源，优先级确定且稳定（高层覆盖低层的同名键）：

| 层 | 优先级 | 说明 |
|---|---|---|
| `BUILTIN_DEFAULT` | 0（最低） | 内置默认值，运行时不允许修改 |
| `EXTERNAL_FILE` | 1 | 外部文件加载的配置 |
| `RUNTIME_INJECTED` | 2（最高） | 运行时通过 API 注入的配置 |

合并规则与异常处置（绝不静默覆盖或吞掉）：

| 情况 | 处置 | 问题标识 |
|---|---|---|
| 同键多层取值 | 高层覆盖低层 | 无（行为确定） |
| 未知配置项 | 忽略该键并告警 | `WARN / UNKNOWN_KEY` |
| 已知字段缺失 | 回退内置默认值并告警 | `WARN / MISSING_FIELD` |
| 取值非法（解析失败 / 越界 / 空串） | 整体拒绝本次更新 | `REJECT / ILLEGAL_VALUE` |

只要存在任何 `REJECT` 级问题，本次合并整体失败，不产出半成品配置；
每条问题都携带键名、来源层与原因，拒绝与告警可明确区分。

## 版本兼容规则

`AppConfig.schemaVersion` 标识配置结构版本：

- 当前版本 `CURRENT_SCHEMA_VERSION = 2`，最小支持版本 `MIN_SUPPORTED_SCHEMA_VERSION = 1`；
- `schemaVersion ∈ [1, 2]`：接受，按对应版本语义生效（旧版本配置保持兼容）；
- 超出范围：`REJECT / ILLEGAL_VALUE`，明细中注明支持范围与来源层，整体拒绝并回退。

## 热更新语义

1. **原子生效**：`RuntimeState`（配置 + 扩展快照 + 版本号 + 生效时间）整体不可变，
   通过 `AtomicReference` 一次性替换。更新过程中并发读取永远看到某一个完整版本，
   绝不读到只生效一半的中间状态。
2. **串行化更新**：所有更新在同一把锁下串行执行，同一时刻的重复或冲突更新
   不会交错；版本号严格递增，生效次数等于版本增量。
3. **失败回退**：更新先完整校验再替换。任何失败保持旧版本完全不变，
   并记录可区分的失败原因（`UpdateFailure.Reason`）：
   - `CONFIG_REJECTED`：配置校验失败（含非法取值、不支持的结构版本、试图修改内置默认层）；
   - `UNKNOWN_EXTENSION`：配置引用了未注册扩展，或扩展替换移除了正在使用的扩展；
   - `EXTENSION_CONFLICT`：提交的扩展集合中 id 重复；
   - `INTERNAL_ERROR`：未预期的内部错误。
4. **状态可观测**：`GET /api/runtime/status` 返回当前生效版本、生效时间与最近一次失败原因。

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/runtime/status` | 当前版本、生效时间、最近失败原因 |
| GET | `/api/runtime/state` | 当前生效的完整状态快照 |
| POST | `/api/runtime/config?layer=EXTERNAL_FILE\|RUNTIME_INJECTED` | 热更新配置（body 为该层完整键值 JSON），失败返回 422 |
| POST | `/api/runtime/extensions` | 热替换扩展集合（body 为 id 列表，如 `["default","shout"]`），失败返回 422 |
| GET | `/api/runtime/greet?name=...` | 用当前生效版本渲染问候语 |

## 验证方法

### 单元测试

```bash
mvn test
```

- `LayeredConfigResolverTest`：优先级合并、字段缺失告警、非法/越界取值拒绝、
  未知键告警、旧版本（schemaVersion=1）兼容、不支持版本拒绝；
- `HotswapRuntimeTest`：更新失败回滚且版本不变、失败原因可区分、
  扩展替换原子性与校验、8 写线程 × 8 读线程并发下读取始终看到完整版本、
  生效次数等于版本增量（无交错）。
- 所有用例日志均打印输入、判定依据与结果（`[用例:...] 输入=... | 判定依据=... | 结果: ...`）。

### 手工冒烟

```bash
mvn spring-boot:run   # 控制台查看实际端口（server.port=0 随机端口）

# 查看状态
curl localhost:$PORT/api/runtime/status
# 非法配置 -> 422，版本不变，失败原因=CONFIG_REJECTED
curl -X POST "localhost:$PORT/api/runtime/config?layer=RUNTIME_INJECTED" \
     -H 'Content-Type: application/json' -d '{"rateLimit":"-1"}'
# 注册 shout 扩展并切换，无需重启
curl -X POST localhost:$PORT/api/runtime/extensions \
     -H 'Content-Type: application/json' -d '["default","shout"]'
curl -X POST "localhost:$PORT/api/runtime/config?layer=RUNTIME_INJECTED" \
     -H 'Content-Type: application/json' -d '{"greetingExtension":"shout"}'
# 验证已热切换：输出 HELLO, HOT!
curl "localhost:$PORT/api/runtime/greet?name=hot"
```
