# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This repository is a conformance implementation, so its version describes *this implementation* — not
the protocol. The protocol is frozen in the main repository and is cited by contract version there;
a number in this file never renames or re-versions a contract.

Each version is written in two blocks — English first, then Chinese — marked as such.

## [Unreleased]

## [0.1.0] - 2026-09-22

**English**

The first release: a KeelBase implementation written in Java, plus the library a generated
application depends on.

### Added

- **The protocol library** (`cn.com.keelbase:keelbase4j-protocol`) — the frozen AI Governance
  Protocol in pure Java, with **no third-party runtime dependency**: canonical JSON, the audit hash
  chain, the delegation token, risk levels, the governance binding, the confirmation lifecycle, and
  the permission/authorization wire contracts. The vendored vectors reproduce on every build, and CI
  diffs that snapshot against the main repository so neither side can move alone.
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

[Unreleased]: https://github.com/rain6fish/KeelBase4J/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/rain6fish/KeelBase4J/releases/tag/v0.1.0
