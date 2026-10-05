# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This repository is a conformance implementation, so its version describes *this implementation* — not
the protocol. The protocol is frozen in its own repository (`rain6fish/keelbase-contract`) and is cited
by contract version; a number in this file never renames or re-versions a contract.

Each version is written in two blocks — English first, then Chinese — marked as such.

## [Unreleased]

## [0.1.1] - 2026-10-05

**English**

A version that matches what this repository says. Content kept landing after the `0.1.0` tag while the
version number stayed where it was, so the number no longer described the tree it was cut from; this
release moves the number to the content.

What is published is unchanged: the parent pom and `cn.com.keelbase:keelbase4j-protocol`, the artifact
a generated application resolves. For the protocol library this release is a **version update only** —
its public API and its compiled classes are identical to `0.1.0`'s, which is measured rather than
assumed: the two published jars carry the same 31 entries, and the only differences are the version
strings in the manifest and the embedded pom. (An earlier version of this note, and the tag message it
was written for, claimed the release carried the PostgreSQL migrations the published `0.1.0` lacked.
It does not: those migrations live in `keelbase4j-core`, which this repository does not publish. The
claim was taken from a roadmap note without being checked, and is corrected here.)

What the repository itself gained since `0.1.0` lives in modules that are **not** published — an
embeddable core, tools a server advertises over MCP, a third SQL dialect, two-person approval for
high-impact actions — and is listed below.

### Added

- **The runtime can be embedded in a host.** It is split into `keelbase4j-core` — an embeddable core
  holding the trust loop, the governance surface and the security chain — and `keelbase4j-runtime`, a
  thin application over it. The core assembles itself through one auto-configuration, and a host's own
  security chain, error handling and identity source coexist with the runtime's rather than displacing
  it. Its configuration classes were renamed to names that cannot collide with a host's.
- **Tools a server advertises** (MCP) are governed exactly like the ones compiled in.
- **A third dialect.** The core ships PostgreSQL migrations beside the H2 and MySQL ones, and a
  generated application carries the driver and the Flyway module it needs. A test applies them and
  runs `ddl-auto=validate` against a real PostgreSQL, rather than reading them.
- **Two-person approval.** A high-impact action is written as a durable row and waits for somebody
  other than the person who asked for it; the write then runs as that person, not as the approver.
  Answers are taken at `approve-by`, and an approved execution that died can be asked for again at
  `retry-execution`.
- **A write is claimed before it runs**, so two identical calls cannot both perform the action. A
  claim whose lease has run out is taken again, so an attempt that died is not a permanent dead end.
- **Policy a deployment can replace** — the rule source, the row range and the department tree become
  defaults, which is what delivery tier B was built to allow.

### Changed

- **A refusal is an event.** It gets an audit line; a revocation reaches the chain (it previously
  wrote the ledger row and nothing else, so the trace could not say who undid what); and a path no
  handler matches arrives as a 404 in the frozen error-body. A host's own exception handler can no
  longer take the runtime's refusal — the order is declared rather than left to registration order.
- **A tool's description carries no governance metadata.** A description reaches a model verbatim, so
  the risk level is where the verdict belongs and nowhere else. A test reads the tools that actually
  ship — in the runtime and in the generator — because the earlier assertion read a hand-written stub
  and passed while a real description leaked.
- **Every rule that grants a subject counts**, not only the first one seen.
- CI gains a third gate: the bilingual-comment rule is now checked rather than left to a reviewer, and
  the checker tests itself before it judges the tree.

### Fixed

- A repeated call no longer writes a row nothing can revoke.
- A row with no department belongs to no department set, instead of raising at the row gate.
- The capability surface names the module, not one of its entities.
- The spec the generator emits from no longer writes a governance verdict into a tool's description.

**中文**

**一版与仓库版本号一致的发布。** `0.1.0` 那个 tag 之后内容继续落地，而版本号停在原地——于是那个号不再
描述它被裁出来的那棵树；本版把**号**挪到**内容**上。

**发布的东西没有变**：父 pom 与 `cn.com.keelbase:keelbase4j-protocol`——生成物要解析的那个 artifact。
对协议库而言，本版**只是版本号更新**——它的公开 API 与**编译产物**与 `0.1.0` 完全相同，这一点是**实测**的
而不是假定的：两个已发布的 jar 条目数同为 31，差异只有 manifest 与内嵌 pom 里的版本串。（本条目**先前
的版本**、以及它当初为之写的 tag message，声称本版承载了已发布的 `0.1.0` 所缺的 **PostgreSQL 迁移**。
**并没有**：那些迁移住在 `keelbase4j-core`，而本仓**不发布**它。那句话是从一条路线图备注里**转述**来的、
**未经核实**，在此更正。）

仓库自身自 `0.1.0` 以来新增的东西，都在**不发布**的模块里——可嵌入核心 · 服务端经 MCP 宣称的工具 ·
第三种方言 · 高影响动作的双人审批——列在下面。

### 新增

- **运行时可以被嵌进宿主**：拆成 `keelbase4j-core`（可嵌入核心：信任闭环 + 治理面 + 安全链）与
  `keelbase4j-runtime`（薄应用）。核心由**一条自动配置**自装配；宿主自己的安全链、错误处理与身份来源
  与运行时**并存**，而不是被顶掉。库的配置类改了名，换成**不可能与宿主相撞**的名字。
- **服务端宣称的工具**（MCP）与编译进来的那些受**同一种治理**。
- **第三种方言**：core 在 H2 与 MySQL 之外带上 **PostgreSQL** 迁移，生成物带上它需要的驱动与 Flyway 模块。
  有测试对**真的 PostgreSQL** 应用迁移并跑 `ddl-auto=validate`，而不是读一遍。
- **双人审批**：高影响动作写成一条持久行，等**发起人以外的人**；随后这次写**以发起人身份**执行，不是以
  审批人身份。在 `approve-by` 作答；一次已批准但死掉的执行可以在 `retry-execution` 再要一次。
- **写在执行前被认领**：两次相同调用不会都执行。跑完租约的认领可被再次取走，所以**崩溃不再是一条死路**。
- **部署方可替换的策略**：规则源、数据范围与部门树都成为默认值——这正是交付档 B 当初要放行的事。

### 变更

- **拒绝是一个事件**：它有自己的审计行；撤销**进链**（此前只写账本行、别的什么都不写，于是轨迹说不出
  「谁撤的」）；没有处理器接手的路径以**冻结 error-body 的 404** 到达。宿主的全局异常处理器**再也拿不走**
  运行时的拒绝——顺序是**声明的**，不再听凭注册顺序。
- **工具描述不带治理元数据**：描述会被逐字送到模型面前，所以那个结论只该由**风险级**承载。守卫读的是
  **真正发货的工具**（运行时与生成器各一处）——此前那条断言读的是**手写的桩**，于是一条真实描述在泄露、
  它却一路绿着。
- **每一条授予主体的规则都算数**，不只是最先看到的那条。
- CI 多一道门：**注释的双语规则**改为受检，而不是留给评审人；且检查器**先自检**、再判全树。

### 修复

- 重复调用不再写下**没有任何撤销路径**的行。
- 没有部门的行**不在任何部门集合里**，而不是在行闸上抛错。
- 能力面报的是**模块**名，不是它某个实体的名字。
- 生成器据以发射的 spec，不再把治理结论写进工具描述。

## [0.1.0] - 2026-09-22

**English**

The first release: a KeelBase implementation written in Java, plus the library a generated
application depends on.

### Added

- **The protocol library** (`cn.com.keelbase:keelbase4j-protocol`) — the frozen AI Governance
  Protocol in pure Java, with **no third-party runtime dependency**: canonical JSON, the audit hash
  chain, the delegation token, risk levels, the governance binding, the confirmation lifecycle, and
  the permission/authorization wire contracts. The vendored vectors reproduce on every build, and CI
  diffs that snapshot against the sources it comes from — the contract, at the version this repository
  answers for, plus the main repository's scenario packs — so neither side can move alone.
- **The runtime** (`keelbase4j-runtime`) — a Spring Boot application whose AI operations run only
  inside the trust loop: Identity → Permission → Governance → Confirmation → Audit → Revoke.
  Delegation-token authentication at the request entry; authorization decided by the frozen contracts
  rather than by role rules in the security configuration; an audit hash chain serialized on a
  database row lock; side effects recorded with revoke; and a confirmation that stays decidable after
  the conversation that raised it has ended.
- **The conversation surface** — `/ai/chat` answers in the shape the frontends already read (a
  superset of the reference's, carrying the governance facts alongside the reply), and
  `/ai/chat/stream` (with its `/admin/...` sibling) is the channel the web console uses. That stream
  stays open while a confirmation is undecided, because some decisions are not the client's to make —
  a wait expiring, or one taken on another surface — and this is the only channel that can carry them.
- **The generator** (`keelbase4j-generator`) — a business request becomes an explicit, reviewable spec
  and then real, standalone Spring Boot source: the files are ordinary `.java` that a developer can
  open and edit, they carry their own governance wiring and migrations, and they do not need this
  repository at runtime.
- **The model seam** (`keelbase4j-springai`) — the runtime's planner is replaceable, and this adapter
  is one implementation of it. A planner proposes; the runtime disposes. No provider is bound here,
  and the governance metadata never reaches the model.
- **The demo deployment** (`keelbase4j-demo`) — the runtime with one provider attached, so "the
  runtime, with a model on the seam" is something you can start rather than something described.
- **Verification alongside the code** — the trust loop, changeability, migration and generated-app
  demos, plus an L3 golden path that drives the frontend's own API modules against this runtime, so
  "one frontend, two runtimes" is measured rather than asserted.

### Changed

- Generated projects depend on the published protocol version, which is filtered from this project's
  own version: a release moves both, so the template cannot be one release behind.

### Notes

- **Published to Maven Central**: the parent pom and `keelbase4j-protocol` (with its sources, javadoc
  and the test jar it attaches) — the artifact a generated application resolves. The runtime,
  generator, adapter and demo are a deployment and studio-side tooling; they opt out of deployment in
  the `release` profile of their own poms.
- The runtime's conversation store is a **transcript, not memory**: no embeddings, no retrieval, no
  memory policy.

**中文**

首次发布：用 Java 写的一份 KeelBase 实现，以及生成物所依赖的那个库。

### 新增

- **协议库**（`cn.com.keelbase:keelbase4j-protocol`）——冻结的 AI 治理协议，纯 Java、**零第三方运行时依赖**：
  规范化 JSON、审计哈希链、委托令牌、风险级、治理绑定、确认生命周期，以及权限/授权的 wire 契约。
  随仓快照的向量每次构建都复现，CI 还会与主仓比对，两边任一方单独移动都会被拦下。
- **运行时**（`keelbase4j-runtime`）——Spring Boot 应用，AI 操作只在信任闭环内执行：
  身份 → 权限 → 治理 → 确认 → 审计 → 撤销。请求入口做委托令牌认证；是否放行由**冻结契约**决定，
  而不是在安全配置里写死角色规则；审计哈希链在数据库行锁上串行；副作用可撤销；确认在提出它的
  对话结束之后**仍可裁决**。
- **对话面**——`/ai/chat` 按前端**已经在读**的形状作答（参照实现的超集，治理事实与回复并列返回）；
  `/ai/chat/stream`（及其 `/admin/...` 兄弟）是 Web 控制台走的那条。这条流在确认未决期间**保持打开**，
  因为有一类决策客户端做不了——等待到期、或别处批的——而这是唯一能把它送达的通道。
- **生成器**（`keelbase4j-generator`）——一句业务请求变成显式可审的 spec，再变成**真实、可独立运行**的
  Spring Boot 源码：文件就是普通 `.java`，能打开能改，自带治理接线与迁移，运行期不需要本仓。
- **模型接缝**（`keelbase4j-springai`）——运行时的规划器可替换，这个适配器是其中一种实现。
  规划器只提议，运行时来裁断；这里不绑任何 provider，治理元数据也不发给模型。
- **演示部署**（`keelbase4j-demo`）——接上一个 provider 的运行时，让「运行时 + 接缝上有个模型」
  成为**能启动**的东西，而不是文档里的一句话。
- **与代码同仓的验证**——信任闭环、变更性、迁移、生成物四个 demo，外加一条 L3 金路径：用**前端自己的**
  API 模块打本运行时，让「一套前端、两个运行时」是**量出来的**而不是宣称的。

### 变更

- 生成物依赖的协议版本改为**从本项目版本过滤而来**：发版时两者一起动，模板不会落后一个版本。

### 说明

- **发布到 Maven Central 的**：父 pom 与 `keelbase4j-protocol`（含 sources、javadoc，以及它附带的
  test jar）——生成物要解析的那个 artifact。运行时、生成器、适配器与 demo 属部署与工作室侧工具，
  在各自 pom 的 `release` profile 里声明不发布。
- 运行时的会话存储是 **transcript，不是 memory**：没有嵌入、没有检索、没有记忆策略。

[Unreleased]: https://github.com/rain6fish/KeelBase4J/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/rain6fish/KeelBase4J/releases/tag/v0.1.1
[0.1.0]: https://github.com/rain6fish/KeelBase4J/releases/tag/v0.1.0
