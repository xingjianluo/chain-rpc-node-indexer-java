# chain-rpc-node-indexer-java

多节点 JSON-RPC 访问 + 区块索引服务（Spring Boot 3 / Java 21）。

- 后台配置多个上游 JSON-RPC 节点，请求在节点间自动故障转移，结果标明来源节点；
- 维护每个节点的健康状态与最新高度，区分四类失败原因，偶发抖动不判死、恢复需连续多次成功；
- 持续抓取并持久化区块，重复抓取 / 重启不重复、不漏块；链重组时回滚到共同祖先后原子重建，
  回滚期间对外查询始终看到完整一致的某一条链；
- **本地即可运行，不依赖任何外部真实节点或付费服务**：上游节点由进程内的模拟端点扮演，
  可通过管理接口制造出块、重组、超时、报错、坏数据、滞后等各种场景。

---

## 1. 快速开始

```bash
mvn spring-boot:run
# 或
mvn -DskipTests package && java -jar target/chain-rpc-node-indexer-java-1.0-SNAPSHOT.jar
```

启动后（默认端口 8080）：

```bash
# 本地索引的最新块（默认数据源，重组语义下的一致读）
curl -s localhost:8080/blocks/latest | jq .

# 按高度查本地索引
curl -s localhost:8080/blocks/3 | jq .

# 直接查上游（多节点故障转移），返回体里的 source 字段是实际提供数据的节点
curl -s 'localhost:8080/blocks/3?source=live' | jq .
curl -s localhost:8080/height/latest | jq .

# 各节点健康状态 / 最新高度 / 连续成败 / 最近失败原因 / 本地索引高度
curl -s localhost:8080/nodes/health | jq .
```

区块默认持久化在 `./data/blocks`（快照文件 + 指针），删除该目录即全新开始。

---

## 2. 节点选择与健康判定规则

### 2.1 失败原因四分类（`ChainErrorReason`）

| 原因 | 触发场景 | 判定位置 |
| --- | --- | --- |
| `NETWORK_UNREACHABLE` | 连接被拒、连接/读取超时、IO 中断 | `HttpRpcTransport`（`ConnectException`/`HttpTimeoutException`/`IOException`） |
| `NODE_ERROR` | HTTP 非 2xx，或响应体携带 JSON-RPC `error` 字段 | `HttpRpcTransport` / `HttpJsonRpcClient` |
| `BAD_RESPONSE` | 响应体不是合法 JSON、不是对象、缺 `result`、区块缺必需字段、高度值无法解析 | `HttpJsonRpcClient` / `DefaultChainNode` |
| `HEIGHT_LAGGING` | 节点可用，但最新高度比集群最高高度落后超过阈值 | `DefaultFailoverChainClient` 交叉验证 |

### 2.2 健康状态机（`HealthRegistry`）

- 初始所有节点 `HEALTHY`；
- 每次失败按**原因**累加“连续失败计数”，并记录 `lastReason`；
- **连续失败达到 `app.failure-threshold`（默认 3）次才置 `UNAVAILABLE`**，
  因此偶发一次抖动不会把健康节点判死；
- 健康节点任意一次成功立即把连续失败计数清零；
- `UNAVAILABLE` 节点**不会再出现在选择候选中**；
- 恢复探测（`RecoveryProbe`，随索引调度执行）只对 `UNAVAILABLE` 节点发轻量探高请求：
  - **单次侥幸成功不恢复**，需**连续成功达到 `app.recovery-successes`（默认 2）次**才恢复 `HEALTHY`；
  - 试探期间一次失败即把连续成功计数清零重来。

### 2.3 选择与故障转移（`NodeSelector` / `FailoverChainClient`）

- 候选只含 `HEALTHY` 节点；排序为“已知最新高度高者优先，同高度按节点 ID 字典序”，保证确定性；
- 取高度采用两阶段：先逐节点探高（失败即切换下一候选），再交叉验证：
  - 以成功节点的最高高度作为集群高度，结果（`LatestHeightResult`）带源节点 ID；
  - 落后集群最高高度超过 `app.lag-threshold-blocks`（默认 5）的节点记一次
    `HEIGHT_LAGGING` 失败（连续多次同样会被判不可用）；
  - 其余节点记成功（清失败计数）；
- 取区块按候选顺序依次尝试，任一节点成功即返回，结果（`BlockResult`）带源节点 ID；
  节点暂时没有该高度的块（`result=null`）不算节点故障，会继续尝试下一个；
- 所有候选都不可用时抛 `NoHealthyNodeException`，REST 层映射为 `503 NO_HEALTHY_NODE`。

关键判定都会打印日志：选中的节点、当前高度、候选尝试顺序、失败原因和判定依据，
例如：

```
[failover] 集群高度=42 选中节点=node-b（候选尝试顺序=[node-a, node-b, node-c]，判定依据：最高高度且id最小）
[health] node=node-a 失败原因=NETWORK_UNREACHABLE 连续失败=3 -> UNAVAILABLE（判定依据：连续失败 3 次达到阈值 3，判定不可用）
[recovery] 探测 node=node-a 成功 height=42 连续成功=2 status=HEALTHY
```

---

## 3. 区块抓取与链重组语义

### 3.1 抓取与去重（`BlockIndexer`）

- 调度器按 `app.poll-interval-ms` 周期性执行：先做一轮不可用节点恢复探测，再追赶一轮；
- 每轮取集群最高高度：
  - 本地落后 -> 抓 `local+1`，校验 `parentHash` 与本地链尖一致才追加，保证**不漏块、不跳号**；
  - 本地与集群等高或更高 -> 仍会拉取集群链尖块与本地同高度块比对哈希（否则会漏掉
    “同高度重组/缩短型重组”）；
- 同高度同哈希的重复写入由存储层幂等忽略，因此**重复抓取、服务重启重放都不会重复写**；
- 上游全部不可用则本轮跳过，本地链不被污染。

### 3.2 重组回滚（reorg）

当新块的 `parentHash` 与本地链尖不一致，或等高比对发现链尖哈希不一致时：

1. 沿新链按 `parentHash` 向父块回溯（全程只读上游，不动本地存储），逐高度与本地记录比对，
   直到找到“同高度同哈希”的**共同祖先**（或回溯到高度 0）；
2. 计算回滚点（共同祖先 +1）与需要重建的新链段，并校验新链段自身哈希链完整；
3. 一次性 `replaceFrom(forkHeight, replacements)`：删除回滚点及以上旧块、写入新链段。

### 3.3 一致性保证（`FileBlockStore`）

存储目录结构：

```
data/blocks/
  meta.json        # {"snapshot":"snapshot-3-h42.json"}，原子替换的指针
  snapshot-N-h*.json  # 不可变全量快照（创世块到链尖的完整数组）
```

- 每次提交（追加一块或整段重组）都生成一份**新的不可变快照**（临时文件写完后原子 rename），
  随后原子替换 `meta.json` 指针，最后发布新的内存视图；
- 读路径基于“当前快照”这一整体引用：`snapshot()` / `get()` / `latest()` 读到的
  **要么是提交前的完整旧链，要么是提交后的完整新链，绝不会出现一半新一半旧**；
- 进程在任意时刻崩溃：meta 未切 -> 重启还是旧链；meta 已切 -> 新链完整生效。
  重启时从 meta 指向的快照恢复并校验高度连续性与哈希链，孤儿快照自动清理。

> 注意：一致性的单位是**单次查询**（一次 `snapshot()` 或一次 `get()`）。需要“整条链”时
> 应使用 `GET /blocks/latest` / `GET /blocks/{height}` 的本地数据源或 `BlockStore.snapshot()`，
> 不要在应用层用多次独立 `get` 拼装跨提交的视图。

---

## 4. REST 接口

| 方法与路径 | 说明 |
| --- | --- |
| `GET /blocks/latest?source=local` | 本地索引最新块（默认，重组一致读）；`source=live` 走上游故障转移 |
| `GET /blocks/{height}?source=local` | 按高度查块；不存在返回 404；`source=live` 走上游 |
| `GET /height/latest` | 实时集群最高高度 + 源节点 `sourceNode` |
| `GET /nodes/health` | 本地索引高度 `indexedHeight` 与各节点状态明细 |

区块响应字段：`source`（`local-index` 或实际节点 ID）、`indexed`、`height`、`hash`、
`parentHash`、`timestamp`、`payload`。

错误响应：`503 NO_HEALTHY_NODE` / `502 UPSTREAM_RPC_ERROR`（`reason` 为四分类之一）/
`500 STORAGE_ERROR` / `400 BAD_REQUEST`。

### 本地模拟管理接口（仅 `app.sim.enabled=true`）

| 方法与路径 | 说明 |
| --- | --- |
| `POST /sim-admin/produce?count=1&payload=...` | 模拟链出块 |
| `POST /sim-admin/reorg?depth=2` | 在距链尖 `depth` 块处制造重组（新链哈希与旧链不同） |
| `POST /sim-admin/nodes/{nodeId}/behavior?behavior=TIMEOUT&lagBlocks=0` | 设置节点行为：`OK`/`TIMEOUT`/`ERROR`/`BAD_RESPONSE`/`LAGGING` |
| `GET /sim-admin/tip` | 查看模拟链链尖 |

---

## 5. 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `8080` | HTTP 端口 |
| `app.nodes[i].id` | - | 节点唯一 ID（用于健康展示与结果来源标注） |
| `app.nodes[i].endpoint` | - | 节点 JSON-RPC 地址（默认指向本进程 `/sim/{id}`） |
| `app.nodes[i].enabled` | `true` | 是否启用 |
| `app.data-dir` | `./data/blocks` | 区块快照持久化目录 |
| `app.request-timeout-ms` | `1000` | 单次 RPC 请求超时（超时归类 NETWORK_UNREACHABLE） |
| `app.failure-threshold` | `3` | 连续失败多少次判为不可用 |
| `app.recovery-successes` | `2` | 不可用节点需连续成功多少次恢复 |
| `app.lag-threshold-blocks` | `5` | 落后集群最高高度多少块记为 HEIGHT_LAGGING |
| `app.poll-interval-ms` | `500` | 索引追赶 / 恢复探测周期 |
| `app.sim.enabled` | `true` | 是否装配本进程模拟节点与管理接口 |
| `app.sim.initial-block-count` | `5` | 启动时预生成区块数（含创世块） |

接真实节点时：把 `app.sim.enabled` 设为 `false`，并把 `app.nodes` 指向真实 JSON-RPC 端点即可
（节点需提供本服务约定的 `chain_blockNumber` 与 `chain_getBlockByNumber` 两个方法；
若对接其它协议，实现 `rpc.ChainNode` / `rpc.JsonRpcClient` 两个接口替换即可）。

---

## 6. 本地验证手册（场景演练）

```bash
mvn spring-boot:run
```

另开终端：

```bash
# 1) 正常链路：查看节点与高度
curl -s localhost:8080/nodes/health | jq '.indexedHeight, .nodes[].status'

# 2) 出块，观察索引自动追赶
curl -s -X POST 'localhost:8080/sim-admin/produce?count=3' | jq .
sleep 2
curl -s localhost:8080/blocks/latest | jq '{height,hash,payload,source}'

# 3) node-a 超时：live 查询应自动切到 node-b/node-c，并在健康接口看到 NETWORK_UNREACHABLE
curl -s -X POST 'localhost:8080/sim-admin/nodes/node-a/behavior?behavior=TIMEOUT' >/dev/null
curl -s 'localhost:8080/blocks/2?source=live' | jq .
curl -s localhost:8080/nodes/health | jq '.nodes[] | select(.nodeId=="node-a")'

# 4) 恢复 node-a（默认连续 2 次探测成功后自动回到 HEALTHY）
curl -s -X POST 'localhost:8080/sim-admin/nodes/node-a/behavior?behavior=OK' >/dev/null
sleep 2
curl -s localhost:8080/nodes/health | jq '.nodes[] | select(.nodeId=="node-a") | .status'

# 5) 节点报错 / 坏数据 / 滞后
curl -s -X POST 'localhost:8080/sim-admin/nodes/node-a/behavior?behavior=ERROR' >/dev/null
curl -s -X POST 'localhost:8080/sim-admin/nodes/node-b/behavior?behavior=BAD_RESPONSE' >/dev/null
curl -s 'localhost:8080/height/latest' | jq .          # 应来自 node-c
curl -s localhost:8080/nodes/health | jq '.nodes[] | {nodeId,lastReason}'

# 6) 链重组：本地索引应回滚到共同祖先后重建，且各高度查询都是新链
curl -s -X POST 'localhost:8080/sim-admin/nodes/node-a/behavior?behavior=OK' >/dev/null
curl -s -X POST 'localhost:8080/sim-admin/nodes/node-b/behavior?behavior=OK' >/dev/null
curl -s -X POST 'localhost:8080/sim-admin/reorg?depth=3' | jq .
sleep 2
curl -s localhost:8080/blocks/latest | jq '{height,hash,payload}'

# 7) 重启恢复：Ctrl-C 停止后重新 mvn spring-boot:run，高度不回退、不重复
```

---

## 7. 测试

```bash
mvn test
```

共 53 个测试（单元 + 全链路集成），覆盖：

- **正常链路**：多节点探高取最高、结果带来源节点；
- **节点超时**（真实 HTTP，`app.request-timeout-ms=400`，请求在约超时时间内切走）；
- **节点报错**（JSON-RPC error / HTTP 500）与**返回格式不对**（坏 JSON / 缺字段），
  断言 `lastReason` 可区分 `NETWORK_UNREACHABLE` / `NODE_ERROR` / `BAD_RESPONSE`；
- **节点滞后**：落后超阈值记 `HEIGHT_LAGGING` 且不被选为高度来源；
- **判死与恢复**：连续失败达阈值才 `UNAVAILABLE`、不再被选中；恢复必须连续多次探测成功；
- **链重组**：同高度重组与缩短型重组，回滚到共同祖先后重建，所有高度查询都是新链、
  快照始终自洽（并发读者读不到新旧混合）；
- **重复抓取**：同高度同哈希幂等忽略；
- **重启恢复**：临时目录持久化，重启后从上次高度继续追加且不重复；
- **REST 语义**：全挂时 `503 NO_HEALTHY_NODE`，健康接口暴露状态/高度/原因。

测试日志中会打印“选中的节点、当前高度、判定依据”，可在 surefire 输出或直接运行
`ChainServiceIntegrationTest` 时查看。

## 8. 代码结构

```
rpc/      JSON-RPC 传输、客户端与链字段解析（原因四分类的起点）
node/     健康注册表、节点选择、故障转移、恢复探测
storage/  区块存储接口与文件快照实现（原子提交、重启恢复）
indexer/  区块索引器（追赶、去重、重组回滚）与定时调度
sim/      本地模拟链、模拟节点 HTTP 端点、管理接口、行为注册表
api/      对外查询 Controller、DTO、全局异常映射
config/   配置属性与 Spring 装配
```
