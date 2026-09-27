# chain-rpc-node-indexer-java

多上游 **JSON-RPC 节点访问 + 区块索引** 的 Spring Boot 服务。纯本地可运行：
默认上游是进程内的**模拟链/模拟节点**（不依赖任何真实节点或付费服务），同时也保留了
基于 JDK `HttpClient` 的真实 HTTP JSON-RPC 节点实现与集成测试。

## 1. 功能一览

- 对外提供链上数据 REST 查询；后台可配多个上游节点，请求自动**故障转移**，响应里带
  **结果来源节点**与该节点当前高度。
- 维护每个节点的**健康状态与最新高度**，区分 4 类失败原因：
  `CONNECTIVITY`（连不上/超时）、`RPC_ERROR`（节点返回错误）、
  `BAD_RESPONSE`（返回内容格式不对）、`LAGGING`（高度落后）。
- **熔断与恢复**：偶发抖动不判死；连续失败达阈值才剔除；恢复必须连续多次成功，
  单次侥幸成功不算恢复。
- 持续抓取区块并落盘：**去重、漏块补齐、重启恢复**；检测到**链重组**时回滚到
  共同祖先再重建，回滚/重建期间对外查询始终读到一份完整一致的快照。

## 2. 快速开始

```bash
mvn spring-boot:run
# 或指定端口
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18088
```

启动后默认有 3 个模拟节点共享一条内存链（初始 12 个块，每 3 秒出一个块），
其中 `node-c` 预置滞后 3 个高度。区块持久化在 `./data/blocks.json`。

REST 接口：

| 接口 | 说明 |
| --- | --- |
| `GET /api/height` | 上游最新高度，返回 `height/sourceNode/nodeHeight` |
| `GET /api/blocks/{height}` | 直接查上游单块（带故障转移与来源节点） |
| `GET /api/chain/head` | 本地索引链头 `indexedHeight/headHash` |
| `GET /api/chain/blocks/{height}` | 本地索引区块（重组期间也保持一致） |
| `GET /api/nodes` | 各节点状态、最新高度、连续成败次数、最后失败原因 |

所有上游都不可用时，查询接口返回 `503`，错误体带最后失败原因分类：

```json
{ "error": "all_nodes_failed", "reason": "CONNECTIVITY", "message": "..." }
```

## 3. 节点选择与健康判定规则

状态机（实现见 `health/NodeHealthTracker.java`）：

```
AVAILABLE ──连续失败≥fail-threshold──▶ UNAVAILABLE
   ▲   ▲                                │ 冷却 probe-cooldown-millis 到期
   │   │                                ▼
   │   └────连续成功 recover-successes── PROBING（每轮最多放 1 个探测节点）
   │                                     │ 任意一次失败
   └─────────────────────────────────────┴──▶ UNAVAILABLE（重新冷却）
   │
   └─高度落后网络最佳 > lag-threshold─▶ LAGGING ─追平─▶ AVAILABLE
```

要点：

1. **一次失败不判死**：`consecutiveFailures` 累加，达到 `fail-threshold`（默认 3）才剔除。
2. **判死后不选中**：`UNAVAILABLE` 不进候选；冷却到期后每轮最多放 1 个进 `PROBING`，
   避免雪崩。
3. **恢复凭连续成功**：`PROBING` 需连续成功 `recover-successes`（默认 2）次才恢复；
   探测期间失败一次立即打回 `UNAVAILABLE`。
4. **滞后不剔除**：`LAGGING` 节点仍可用，但排序在健康节点之后；健康节点全挂时仍兜底。
5. **候选排序**：`AVAILABLE`（按最新高度降序）→ 至多一个 `PROBING` → `LAGGING`。
6. **主动探活**：`HealthProbe` 周期性探测所有节点高度，让从不被选中的滞后节点
   也会被及时标记，并让不可用节点冷却后被探活。
7. 每次选择/切换/判定都会打印日志（选中节点、节点高度、网络最佳高度、判定原因）。

失败原因如何产生（见 `rpc/JsonRpcCodec.java`、`HttpJsonRpcNode.java`）：

- `CONNECTIVITY`：连接被拒、读超时、IO 异常；
- `RPC_ERROR`：HTTP 非 2xx，或响应是合法 JSON-RPC 但带 `error` 字段；
- `BAD_RESPONSE`：响应不是合法 JSON、不符合 JSON-RPC 结构、result 缺字段/类型错/
  区块高度与请求高度不一致；
- `LAGGING`：成功取到高度，但低于“网络最佳高度”（所有节点报告值的最大值）超过阈值。

## 4. 重组回滚语义

实现见 `index/ChainIndexer.java`。

1. 每轮先拿网络高度，再对**链头高度**（取 `min(本地头, 网络头)`）抓块比对 hash——
   即使重组后链高度不变也能发现（只看高度会漏掉“等长重组”）。
2. hash 一致：从本地链头 + 1 顺序补块；高度已存在且 hash 一致直接跳过
   （重复抓取/重启幂等，不重不漏）。
3. hash 不一致：判定重组，沿新块的 `parentHash` 向上找到**共同祖先**，
   丢弃共同祖先之后的全部旧块，再按新链顺序补齐（受 `max-blocks-per-tick` 限速，
   没补完下一轮继续）。
4. **一致性保证**：重建期间只操作内存中的工作副本；构建完成后，
   `原子落盘（临时文件 + rename）` 与 `volatile 快照引用替换` 在同一把锁内完成。
   查询始终读某个不可变的 `ChainSnapshot`，因此绝不会读到“一半新一半旧”。
5. 所有节点都不可用时跳过本轮，已提交快照保持不变。

存储：`store/FileBlockStore.java` 单文件 JSON，临时文件 + 原子 move 覆盖，
进程崩溃也只会留下“上一份完整快照”或“新快照”。

## 5. 配置项（`application.properties`）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `server.port` | 8080 | HTTP 端口 |
| `app.data-dir` | `./data` | 区块文件目录（`blocks.json`） |
| `app.health.fail-threshold` | 3 | 连续失败多少次判 UNAVAILABLE |
| `app.health.recover-successes` | 2 | PROBING 恢复所需连续成功次数 |
| `app.health.probe-cooldown-millis` | 1000 | UNAVAILABLE 冷却多久后允许探测 |
| `app.health.lag-threshold` | 2 | 落后网络最佳高度多少即 LAGGING |
| `app.health.probe-interval-millis` | 2000 | 主动探测定时间隔 |
| `app.indexer.enabled` | true | 是否启用后台索引 |
| `app.indexer.tick-millis` | 500 | 索引轮询间隔 |
| `app.indexer.max-blocks-per-tick` | 100 | 每轮最多处理块数（含重组重建） |
| `app.simulated.chain-height` | 12 | 模拟链初始块数（含创世块） |
| `app.simulated.mine-interval-millis` | 3000 | 模拟出块间隔 |
| `app.upstreams[i].*` | 内置 3 节点 | 自定义上游，见下 |

自定义上游（可混用模拟节点与真实 HTTP 节点）：

```properties
app.upstreams[0].name=local-http
app.upstreams[0].type=http
app.upstreams[0].url=http://127.0.0.1:9000
app.upstreams[0].timeout-millis=1000
app.upstreams[1].name=mock-backup
app.upstreams[1].type=simulated
app.upstreams[1].lag-by=0
app.upstreams[1].timeout-millis=1000
```

HTTP 节点约定 JSON-RPC 2.0：`chain_getLatestHeight`（`result.height` 为数字）、
`chain_getBlockByHeight`（`result` 为区块对象，字段
`height/hash/parentHash/timestamp/payload`）。

## 6. 本地验证方法

```bash
mvn test
```

测试清单（共 29 个，`src/test/.../tests`）：

| 测试类 | 覆盖内容 |
| --- | --- |
| `JsonRpcCodecTest` | 正常解析；RPC error→RPC_ERROR；坏 JSON/缺字段/高度错位→BAD_RESPONSE |
| `NodeHealthTrackerTest` | 抖动不判死、连续失败熔断、冷却探测、连续成功恢复、探测失败回退、滞后标记与排序、4 类原因可区分 |
| `ChainQueryServiceTest` | 正常链路带来源节点；超时/报错/坏响应故障转移；全失败原因；滞后兜底 |
| `ChainIndexerTest` | 全量同步、重复 tick 幂等、增量补块、重组回滚共同祖先、深度重组+并发一致性、重启恢复不重不漏、全节点不可用保快照 |
| `HttpJsonRpcNodeIntegrationTest` | JDK 内置 HttpServer：HTTP 正常、连接拒绝→CONNECTIVITY、500/JSON-RPC error→RPC_ERROR、坏 JSON→BAD_RESPONSE |
| `ApplicationEndToEndTest` | 整个 Spring 应用 + REST：索引追平、来源节点、`/api/nodes` 健康视图、LAGGING |
| `FailoverRecoveryEndToEndTest` | REST 层：持续故障切换、连续成功恢复、偶发抖动不打死 |
| `NoCandidateTest` | 没有任何可用节点时抛 `AllNodesFailedException`（对应 503） |

单测日志中会打印**选中的节点、当前高度与判定依据**，例如：

```
动作[查询最新高度] 节点 [a] 失败，判定原因[CONNECTIVITY]，切换下一节点
动作[查询最新高度] 选中节点 [b] 成功，节点当前高度 30，网络最佳高度 30
检测到链重组：高度 7 本地 hash 0x.. 与新链 0x.. 不一致，回滚到共同祖先高度 6
节点 [a] 连续失败 3 次达到阈值，判定 UNAVAILABLE，最后原因 RPC_ERROR
```

手动验证故障转移：默认配置下所有节点都是模拟节点，可在调试中对某个
`SimulatedUpstreamNode` 调用 `failNext(n, FailureReason.X)` /
`setAlwaysError(true)` / `setAlwaysMalformed(true)` / `setLatencyMillis(..)` /
`setLagBy(..)` 注入故障，或调用 `SimulatedChain.reorg(from, len)` 注入重组。

## 7. 主要代码结构

```
chain/Block.java                       区块模型（height/hash/parentHash/...）
rpc/UpstreamNode.java                  上游节点接口
rpc/JsonRpcCodec.java                  JSON-RPC 编解码与错误分类
rpc/SimulatedChain.java                内存链（出块/重组）
rpc/SimulatedUpstreamNode.java         模拟节点（超时/报错/坏响应/滞后注入）
rpc/HttpJsonRpcNode.java               真实 HTTP JSON-RPC 节点（JDK HttpClient）
health/NodeHealthTracker.java          健康状态机 + 候选选择
health/HealthProbe.java                周期主动探活
query/ChainQueryService.java           故障转移查询（结果带来源节点）
store/FileBlockStore.java              原子提交的单文件存储
index/ChainSnapshot.java               不可变链视图（一致性基础）
index/ChainIndexer.java                抓取/去重/重组回滚重建
web/ChainApiController.java            REST 接口
config/AppProperties.java / AppConfig  配置与装配
```
