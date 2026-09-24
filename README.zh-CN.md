# KeelBase4J

**KeelBase AI 治理协议的 Java/Spring 载体**——让 Java 与 Spring 应用能与其他 KeelBase 实现运行在同一套治理边界内，并且用「复现同一批冻结的、语言无关的向量」来证明这一点。

<p align="center">
  <a href="https://github.com/rain6fish/KeelBase4J/actions/workflows/ci.yml"><img src="https://github.com/rain6fish/KeelBase4J/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License: Apache-2.0"></a>
  <img src="https://img.shields.io/badge/Java-17-informational" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring_Boot-4.1.1-informational" alt="Spring Boot 4.1.1">
</p>

**设计规则——实现冻结的契约，不翻译参照实现。**
KeelBase 协议才是唯一真源，而不是恰好最先实现它的那份 TypeScript 代码。兼容性由「复现契约里那批语言无关的向量」来证明——那是任何别的实现都该用的同一批文件。

---

## 这是什么

KeelBase（参照实现是 TypeScript，位于 `rain6fish/KeelBase`）定义了一套语言无关的 AI 治理协议：审计哈希链、委托令牌、工具风险级、确认生命周期，以及权限/授权线缆契约。

本仓是这套协议的 **Java 侧**，装着两样东西：

- **一个运行时**——一个 Spring Boot 应用，它的 AI 操作只在信任闭环内运行（身份 → 权限 → 治理 → 确认 → 审计 → 撤销）；
- **一个生成器**——业务描述 → Business Spec → 真实、可独立运行、可手改的 Spring Boot 源码。

KeelBase4J 是**载体**，不是第二个产品：它的存在，是让 Java/Spring 团队能拿到同一套治理语义，也是让「协议是语言中立的」这句话背后站得住第二个实现。

---

## 30 秒

需要 JDK 17+ 与 Maven。

```bash
mvn test
```

**测试全绿**——`mvn test` 覆盖协议库、运行时、生成器与适配器；实时状态看上方 CI 徽章，而不是写在这里的一个数字。下面每个主张都能真跑出来——四个脚本，前三个不需要模型：

```bash
bash scripts/demo-generated-app.sh    # 生成 → 构建 → 运行 → 走一遍信任闭环
bash scripts/demo-changeability.sh    # 变更 → 再生成 → 手改存活 → 新规则生效
bash scripts/demo-migration.sh        # 变更 → 加性迁移 → 存量数据跟着走

export DEEPSEEK_API_KEY=...           # demo 模块也可用 -Popenai 或 -Pollama 构建
bash scripts/demo-springai.sh         # 真模型接在规划器接缝上
```

任一预期结果缺失，脚本即非零退出。它们没有一个是冒烟测试——每个脚本都是下面某一条主张的证据。

---

## 信任闭环

```text
请求（身份） ─► 选定工具 ─► 门控（风险级）
        │                                   │
        │            ALLOW ────────────────►├─► 执行 ─► 副作用 ─► 审计
        │            CONFIRM ──► 令牌 ──► 待确认（什么都没写）
        │                                   │        └─ 批准 ─► 执行 ─► 副作用 ─► 审计
        │            BLOCK ────────────────►└─► 拒绝（从不执行）
        ▼
   撤销 ─► 本地补偿（软删除） ─► 副作用状态 revoked
   校验 ─► 重算审计哈希链
```

每一步都是**运行时**施加的，不是提示词里描述的。有三点要明说，因为这正是它区别于「给模型套一层指令」的地方：

- **认证发生在请求入口，且只在那里。** Spring Security 回答*这是谁的请求*；KeelBase 回答*这个 AI 行为许不许发生*。那套配置里刻意没有 `hasRole`、没有 `@PreAuthorize`、没有任何 URL 到角色的规则——对同一个问题给出第二个答案，就是一个会悄悄分叉的答案。
- **委托令牌只证明一个主体，仅此而已。** 它不带角色，因为委托从不提权。运行时把验过的主体映射到本地用户与角色。此前那个从请求里读 `X-User-Id` / `X-User-Role` 的适配器已经删掉——这些头被忽略，只带它们的请求是 401。
- **规划器提议，运行时分派。** 「调什么」是一个可替换的接缝（`ToolCallPlanner`）。它的输出是工具名加参数——仅此而已。风险级、是否确认、审计与撤销，都来自工具自己的声明，由下游无条件施加。治理元数据从不发给模型。

对一行的访问是两个分开的问题：能力门（*这个动作作用在这个主体上许不许发生*）与数据范围（*哪些行*——`own`、`org`、`own_dept`、`own_dept_and_below`、`custom_dept`、`all`）。行由**类型化谓词**划定，从不拼 SQL 字符串；事实缺失时范围**收紧**而不是放宽。

设计理由与完整组件地图：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

---

## 哪些是已验证的

下表每一条都由本仓自带的测试或脚本检查。中列是去哪儿看，末列是该主张诚实的边界。

| 主张 | 证据 | 边界 |
|---|---|---|
| 冻结协议向量被复现 | 8 个随仓向量 → `CanonicalJsonTest` · `AuditChainTest` · `DelegationTokenTest` · `RiskLevelTest` · `GovernanceBindingTest` · `ConfirmationLifecycleTest` | 快照必须与主仓一致，由 CI 的 `vector-drift` 拦 |
| 失败语义按向量处置 | `FailureSemanticsTest` | — |
| 调用方不能自称身份或权限 | `AuthenticationTest` | 身份是薄 SPI；默认适配器把主体映射到已声明的本地事实 |
| 能力决策与数据范围彼此分离 | `PermissionWireTest` · `AuthorizationMappingTest` · `ScopeFilterTest` | 规则是代码里声明的（交付档 A） |
| 没有人批准，写操作不执行 | `TrustLoopTest` | — |
| 一个确认令牌恰好执行一次工具 | `ConfirmationConcurrencyTest`（先写会红的测试）· `demo-generated-app.sh` 里的八并发检查 | — |
| 审计链防篡改，且串行追加在行锁上 | `AuditChainConcurrencyTest` · `GET /audit/verify` | spike 的存储是内存库，故为进程内 |
| 生成物可独立运行 | `demo-generated-app.sh` | 生成器处理的是它所针对的 CRM 形状请求 |
| 变更携带库中已有的数据 | `demo-migration.sh` | Flyway 拥有 schema；`ddl-auto=validate` |
| 再生成会合并开发者的手改 | `demo-changeability.sh` | **行级**合并：合并的是文本，不是语义 |
| 真模型能路由，且运行时照样扣住写操作 | `demo-springai.sh`（DeepSeek 真 key） | 需要 key；是人跑的 demo，不是 CI 门禁 |
| 前端自己的模块能驱动本运行时 | `demo-golden-path.sh` | 控制台的 API 模块打真实例；浏览器界面本身属主仓 |

以上每一条都在本检出上实测——一次 `mvn test` 加几个无需 key 的 demo，每个 demo 跑的时候会把自己检查的项打出来。

---

## 这不是什么

引用上面任何内容之前，先读这一节。

1. **不是产品。** 没有 Docker 镜像、没有在线演示、也没有自带界面。KeelBase 的前端在主仓，那套前端**自己的**
   API 模块可以直接打本运行时（`scripts/demo-golden-path.sh`），但本仓没有面向浏览器的打包。
2. **不是完整的生成器。** `BusinessSpecParser` 是「它识别的那几种请求形状」上的确定性路由——CRM 形状的初始请求，以及编辑该 spec 的增量变更请求。它不会把任意自然语言变成模块，也不调用模型。
3. **不是智能体框架。** 没有 RAG、没有向量化、没有记忆、没有子智能体、没有主动式 AI。这些是 ADR-0004 里的显式非目标；要解冻其中任何一项，都得另开 ADR。
4. **不是存量系统的桥。** 这里没有 MCP 或 OpenAPI 接入。给存量系统加治理的那个 Java 侧桥在另一个仓（`rain6fish/KeelBase-java-starter`）。
5. **不替代主仓。** 本仓只**消费**协议。协议变更先在主仓落地（向量 → 实现）；两仓漂移由 CI 拦下，不容忍。
6. **不是生产级的数据与身份。** 运行时用的是内存 H2，授权规则在代码里声明，没有 IdP、没有 `roles`/`permissions` 表、没有多租户。真实目录或 IdP 是同一个接缝背后的适配器——不在这里实现。
7. **定位不在这里裁定。** 这条线是否、如何成为产品，由内部决策记录裁定，不在本仓。本仓持有的是 Java 载体，以及「它复现了协议」这件事的证据。

---

## 模块

五个 Maven 模块，依赖**单向**：`runtime` 与 `generator` 依赖 `protocol`，绝不反向；没有任何模块依赖 runtime。

| 模块 | 内容 | 依赖 |
|---|---|---|
| `keelbase4j-protocol` | 冻结的协议——规范 JSON、审计哈希链、委托令牌、风险级、治理绑定、确认生命周期，以及权限/授权线缆契约 | **无**（仅 JDK） |
| `keelbase4j-runtime` | 受治理的运行时——AI 操作在信任闭环内运行 | `protocol`、Spring Boot |
| `keelbase4j-generator` | 业务描述 → Business Spec → 可独立运行的 Spring Boot 源码 | `protocol` |
| `keelbase4j-springai` | 适配器——用 Spring AI 实现运行时的 `ToolCallPlanner` 接缝；未配模型时完全惰性 | `runtime` |
| `keelbase4j-demo` | 可运行部署——运行时 + 适配器 + **一个** provider，由 Maven profile 选（默认 `deepseek`，另有 `openai`、`ollama`） | `runtime`、`springai` |

`keelbase4j-protocol` 正是**生成物所依赖**的那个 artifact，所以它保持零第三方依赖：当它与运行时共用一份 jar 时，那个库悄悄带上了 Spring Security，而继承了其自动配置的生成应用把所有端点都锁死了。适配器——模型 provider、身份 provider——属于运行时**之外**，依赖它，而不是反过来。

运行时服务十四条路由，全部挂在参照实现的 `/api/v1` 前缀下（`server.servlet.context-path`），这样一套 runtime-neutral 前端只保留一个 base URL，不需要按 runtime 分支：

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/ai/chat` | 规划器 → 受治理的工具调用 |
| POST | `/ai/chat/stream` · `/admin/ai/chat/stream` | 同一回合的 SSE 形态；`/admin` 那条要求管理角色 |
| POST | `/ai/confirmations/{token}` | `approve`（执行）或 `decline`（什么都不写） |
| GET | `/ai/tool-effects` | 已记录的副作用 |
| DELETE | `/ai/tool-effects/{id}` | 撤销 → 本地补偿（软删除） |
| GET | `/audit/verify` | 重算并校验审计哈希链 |
| GET | `/auth/me` | 调用方是谁——按本部署所知道的 |
| GET | `/auth/me/permissions` | 调用方的能力清单，按冻结契约形状返回 |
| GET | `/auth/oauth/providers` | 本部署提供哪些联邦登录——没有，这就是答案 |
| POST | `/auth/login-stats` | 登录页的访问上报，故意答 `ok:false`：没有落点可记 |
| GET | `/customers` | 按调用方的数据范围收窄 |
| GET | `/app/capabilities` · `/app/provenance` | 这个部署对自己声明了什么 |

逐组件细节：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

---

## 生成器

```text
业务描述
   → BusinessSpecParser → BusinessSpec        （实体、字段、归属规则、AI 工具及其风险）
   → JavaGenerator      → Spring Boot 工程     （真实 .java 源码 + pom.xml + README）
```

产物是**普通、可手改的源码**——一个能自己构建的 Maven 工程，自带治理接线，只依赖冻结协议那个**库**，从不依赖 KeelBase4J 的运行时服务。生成的应用自带身份接缝、契约派生的授权（`GET /auth/me/permissions`，与运行时同路径同形状）、Flyway 拥有的 schema 和确认存储——所以它做出的治理决策就是协议定义的决策，而且全程不需要本仓在场。

再生成是相对「上一次生成的内容」（`.keelbase/baseline/`）做**三方合并**，所以手改写在文件任何位置都能存活；生成器从未产出过的文件原样保留并上报，真正的冲突也是上报而不是猜着解决。

```bash
bash scripts/demo-generated-app.sh    # 在产物上：生成 → 构建 → 运行 → 信任闭环
bash scripts/demo-migration.sh        # 变更携带存量行
bash scripts/demo-changeability.sh    # 手改在变更后存活
```

本节主张的边界见[「这不是什么」](#这不是什么)第 2、3 条。

---

## 可移植性：第二载体

本仓是「一个 KeelBase 实现可以用另一种语言写出来、并且仍是**同一个**协议、而不是长得像的协议」的第一份证明：

- **8 个冻结向量**随仓放在 `conformance/vectors/`，被逐条断言复现——规范 JSON（字节级忠实于 `JSON.stringify` 的键排序语义，含 UTF-16 键序与 JavaScript 数字格式）、审计哈希链、委托令牌、风险级、治理绑定、确认生命周期与失败语义。
- **线缆契约是复现的，不是重新发明的。** `permission-decision`、`permission-capability-list`、`org-membership-scope`、`authorization` 都承载在 `cn.com.keelbase.protocol` 里，并按冻结形状对外提供。
- **向量是只读快照。** CI 的 `vector-drift` 作业在每次推送时把它们与主仓的权威副本比对，所以任何一侧都无法单独漂移。这里从不手改它们。

---

## 协议真源

权威协议住在它自己的仓——[`rain6fish/keelbase-contract`](https://github.com/rain6fish/keelbase-contract)：
语言中立的向量、线缆对象 schema，以及两者共用的那一条版本线。两个 runtime 都消费它，**谁也不拥有它**。

- 契约仓——决定一致性判定的向量与 schema；
- 主仓的 `docs/protocols/ai-governance-protocol.md`——协议的散文表述（§2 链 · §3 令牌 · §4 风险级）；
- §5.1——一个实现如何对着这些向量自证。

`conformance/vectors/` 下是快照；刷新方式见 [conformance/vectors/README.md](conformance/vectors/README.md)。

## 仓库

- [`keelbase-contract`](https://github.com/rain6fish/keelbase-contract)——协议本体，独立于任何实现
- [`KeelBase`](https://github.com/rain6fish/KeelBase)——TypeScript runtime 与产品文档
- `KeelBase4J`——本仓，同一协议的第二载体
- [`KeelBase-java-starter`](https://github.com/rain6fish/KeelBase-java-starter)——桥接的 Java 侧 Spring Boot Starter

## 兼容矩阵

协议有自己的一条版本线，独立于任何 runtime 的版本。下表说明**每个 runtime 对着哪一版契约应答**——
也就是它已承诺会说哪些对象。

| Runtime | Runtime 版本 | Contract 版本 |
|---|---|---|
| [`KeelBase`](https://github.com/rain6fish/KeelBase)（TypeScript） | `v1.0.11` | **v1.0.1**——树内携带；该发布早于契约仓的建立 |
| [`KeelBase`](https://github.com/rain6fish/KeelBase)（TypeScript） | `main`，**未发布** | **v1.1.0**——以 submodule 绑定 |
| `KeelBase4J`（Java） | `v0.1.0` | **v1.1.0**——vendor 快照，从主仓刷新 |

`conformance/vectors/` 下的快照是一份副本，而它下来的那条链值得知道：契约住在自己的仓，主仓把它绑成
submodule，而这份快照是**从主仓**刷新的。所以它是**一份副本的副本**，而 CI 里的漂移门只查**最后一环**——
它能抓到这份快照落后于主仓，**抓不到主仓落后于契约**。

补上这一环是另一步，尚未做。

## 文档

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)——子系统、组件版本、信任闭环，以及授权/身份层的决策
- [CLAUDE.md](CLAUDE.md)——本仓据以建立的红线（依赖方向、协议为唯一真源、生成物必须是真实源码）
- [conformance/vectors/README.md](conformance/vectors/README.md)——随仓快照

## 许可

Apache-2.0。
