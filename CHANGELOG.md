# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This repository is a conformance implementation, so its version describes *this implementation* — not
the protocol. The protocol is frozen in its own repository (`rain6fish/keelbase-contract`) and is cited
by contract version; a number in this file never renames or re-versions a contract.

Each version is written in two blocks — English first, then Chinese — marked as such.

## [Unreleased]

## [0.1.5] - 2026-10-10

**English**

### Added

- **The audit chain reads back.** The runtime could append to the chain and verify it, but nothing could
  hand the rows out — so "which rows did this caller leave in this period" had no answer at all, and a
  run's operator could not point at the rows their run put there. `GET /api/v1/audit/logs` answers the
  frozen `ai-audit-log-row`, filtered by caller, period and outcome, and `ai_audit_logs` gains an
  `is_error` column (h2, mysql, postgresql) kept deliberately **outside** the hashed payload: folding it
  in would make every row already written unverifiable and report a deployment's own history as a broken
  chain. The filters the reference offers over columns this runtime does not record — agent,
  organisation, and the authorization-verdict view — are **refused** rather than ignored, because a list
  that looks filtered and is not cannot be told from one that is.

- **A side effect carries what the tool produced.** `/api/v1/ai/tool-effects` answered an item that
  matched neither branch of the frozen `side-effect-revoke`: it carried `argsHash`, which belongs to
  `traceItem`, without that branch's snapshots. The write path now captures the tool's result where it
  still is what the decision produced, `SideEffect` stores it (`after_snapshot`, three dialects), and the
  item answers `traceItem`. One stored column was being written into two contract fields whose
  vocabularies differ; they are now derived apart, so a row nobody tried to revoke reports no revoke word
  rather than the effect's own status.

### Changed

- **`POST /api/v1/ai/chat` answers the frozen `chat-response` and nothing more.** The answer used to
  carry `status`, `data`, `token`, `effectId` and `error` alongside the conversation turn, which the
  contract forbids (`additionalProperties: false`), which the reference never did on that path, and
  which the protocol's prose contradicts — a non-streaming call returns no confirmation token. The facts
  moved rather than vanished: a tool call's result is reported as events on `/api/v1/ai/chat/stream`, and
  a pending write is found in `/api/v1/ai/my/confirmations`, whose items carry the token. `GET
  /api/v1/audit/verify` now returns the chain it walked, and is gated at administrator as the object's
  own implementation gates it.

- **The generated application follows on both surfaces.** Its chat answer is the frozen object too, and
  its effect row answers `traceItem`. A generated application serves no confirmation list, so a
  non-streaming caller there reaches a pending write through the stream — the way the reference's callers
  do.

**中文**

### 新增

- **审计链读得回来了。** 运行时能给链追加、能校验它，却**没有任何东西把行交出来** —— 于是「这个调用方在这一段
  时间里留下了哪几行」**根本没有答案**，一次运行的操作者也**指不出**自己那次留下的行。`GET /api/v1/audit/logs`
  按冻结的 `ai-audit-log-row` 作答，可按调用者、一段时间与结果筛选；`ai_audit_logs` 加一列 `is_error`
  （h2、mysql、postgresql），**刻意留在哈希载荷之外**：折进去会让**已经写下的每一行**都验不过，并把一个部署
  **自己的历史**报成断链。参照实现另有的、而本运行时**不记那些列**的筛选 —— agent、组织、授权结论视图 ——
  一律**拒绝**、不是忽略，因为一份**看着像筛过、其实没有**的列表，与真筛过的分不出来。

- **副作用带上工具产出的东西。** `/api/v1/ai/tool-effects` 此前回的项**两个分支都不满足**冻结的
  `side-effect-revoke`：它带着属于 `traceItem` 的 `argsHash`，却没有那个分支要的快照。写路径现在**在结果仍然是
  这次决策产出的那个样子时**捕获它，`SideEffect` 把它存下来（`after_snapshot`，三方言），项改答 `traceItem`。
  另有一条存储列被同时写进契约里**词表不同**的两个字段；现在分开推导，于是一行**没人试过撤销**的记录报的是
  **没有**撤销词，而不是把 effect 自己的状态抄进去。

### 变更

- **`POST /api/v1/ai/chat` 答的就是冻结的 `chat-response`，别无其它。** 这条答案过去在对话回合之外还带
  `status`、`data`、`token`、`effectId`、`error` —— 契约禁止（`additionalProperties: false`）、参照实现在
  那条路径上从不这么做、协议散文也与它相悖（**非流式调用不返回确认 token**）。那些事实是**搬家**、不是消失：
  一次工具调用的结果在 `/api/v1/ai/chat/stream` 上以**事件**报出，待确认的写在 `/api/v1/ai/my/confirmations`
  里找（它的项**带着 token**）。`GET /api/v1/audit/verify` 现在把它走过的链一并回出来，并按这个对象**自己的
  实现**守在管理员上。

- **生成的应用在两个面上都跟上了。** 它的聊天答案也是冻结对象，它的效应行也答 `traceItem`。生成的应用**没有**
  确认列表，所以它这边的非流式调用方要经**流式**够到待确认的写 —— 参照实现的调用方走的也是这条路。

## [0.1.4] - 2026-10-08

**English**

### Added

- **A tool declares the arguments it reads, and the gate checks every proposal against that
  declaration.** `AiTool.parameters()` — a `default` returning an empty list, so nothing compiled
  against an earlier version breaks — and `ToolParameter`: a name, a type drawn from the vocabulary the
  conformance vectors already use, a description, and whether it is required. The engine checks in
  `execute`, after the block decision and before any confirmation row exists: an argument the tool does
  not read, or a required one left out, is refused with `invalid_arguments`, an audit line, and no row
  for a person to answer. The refusal is an outcome rather than an exception because the framework's
  own tool-calling loop hands it back to the model, which is what lets it correct itself and propose
  again.

- **The declared arguments are also what the model is shown.** `GovernedToolCallbacks` builds the tool
  definition's `inputSchema` from them, and the plain planner's catalogue lists them beside each tool.
  Until this, a tool reached the model as an object with no stated properties, so the model had to
  guess the names — and the guess is what produced a confirmation somebody approved and an execution
  that wrote nothing.

- **The MCP adapter forwards the input schema it has always held.** `McpSchema.JsonSchema` was captured
  and never passed on; once arguments are checked, an adapter that keeps it to itself refuses every MCP
  call for carrying arguments the tool is not known to read.

### Changed

- **A tool that declares nothing is not checked.** The empty default is deliberate: a deployment's own
  tools and every generated application keep working untouched, and a tool that has not yet said what
  it reads is not silently read as taking no arguments.

**中文**

### 新增

- **工具声明它读哪些入参，闸口拿这份声明核对每一份提议。** `AiTool.parameters()` —— 一个
  `default`、返回空表，故对着更早版本编译的东西一个都不破 —— 与 `ToolParameter`：名字、类型（取自
  conformance 向量早已在用的那套词表）、描述、以及是否必填。引擎在 `execute` 里核对，位置在 block 判定
  **之后**、任何确认行存在**之前**：工具不读的参数、漏掉的必填参数，都以 `invalid_arguments` 被拒、留一行
  审计、**不产生等人回答的行**。拒绝是**结果**而不是异常，因为框架自己的工具调用循环会把它交回模型 ——
  那正是模型能自己改对、再来一次的原因。

- **这份声明同时也是给模型看的东西。** `GovernedToolCallbacks` 按它生成工具定义的 `inputSchema`，朴素
  规划器的目录也在每个工具旁列出它。在此之前，工具是以一个**没有声明任何属性**的对象到达模型的，模型只能
  去猜名字 —— 而那个猜测，正是「一条被人批准的确认 + 一次什么都没写的执行」的来源。

- **MCP 适配器转发它一直握着的 input schema。** `McpSchema.JsonSchema` 此前被捕获、却从未转出去；在入参
  已受校验之后，一个把它留给自己的适配器会让**每一次 MCP 调用**都因「带了工具不知会读的参数」而被拒。

### 变更

- **什么都没声明的工具不被校验。** 空默认是刻意的：部署自己的工具、以及每一套生成应用，都原样继续工作；
  而一个还没说过自己读什么的工具，**不会被悄悄读成「不接受任何参数」**。

## [0.1.3] - 2026-10-07

**English**

### Added

- **The multi-step path has a seam of its own, and a route.** `TaskRunner` joins `ToolCallPlanner` as
  this runtime's second AI seam: one message, and the framework's own calling loop takes as many
  governed steps as it needs — with every step still arriving at the engine, because the callbacks it
  invokes have no other path. The seam and `POST /ai/task` live in the runtime rather than in an
  adapter, so a host that embeds this runtime gets the route without adding one of its own, and the
  deployments that drive it share **one** implementation instead of one each.

- **A deployment with no orchestration adapter is told so, not refused.** `POST /ai/task` answers
  `available: false` with a reason and a next step, as a 200 — the shape this runtime already uses
  where a deployment lacks something (`/auth/oauth/providers` answers an empty list). Two cheaper
  answers are both wrong: a 5xx reaches the caller as "服务器内部错误" and says nothing about what is
  missing, and an empty run would claim that a run happened.

- **The Spring AI adapter drives that seam.** `GovernedToolCallbacks` hands the model the runtime's
  tools as callbacks whose only path is the engine, and the model is shown name and description only —
  no risk level, no confirmation flag, no revoke class. `GovernedTaskRunner` implements `TaskRunner`,
  and a run reports every step in order with the tool it was an attempt at, plus the token of a step
  still waiting on a person.

### Changed

- **A run's shape is the seam's, not an adapter's.** `TaskRun` and its `Step` moved from the adapter
  into the runtime: what a route answers with belongs to the runtime that answers. For anyone who
  compiled against the adapter's own copy in 0.1.2 — published hours before this — that is a source
  break: the type is gone and the list accessor is now `steps()` rather than `calls()`.

- **This release is driven by a test rather than only by a demo.** The loop now has one that runs
  without a model — a stand-in that *advertises* tool calling behind a client that carries the
  tool-calling advisor — so "the framework's call reaches the engine, and what it said comes back in
  the run" is checked on every push. That pair is not incidental: a stub that does not advertise the
  capability has its tools stripped from the request, and a client without the advisor never sends
  them, which is what a previous attempt at this was measuring without knowing it.

**中文**

### 新增

- **多步路径有了自己的接缝，也有了自己的路由。** `TaskRunner` 与 `ToolCallPlanner` 并列成为本运行时的**第二条**
  AI 接缝：一句话进去，由**框架自己的调用循环**走完它需要的若干受治理步骤 —— 而每一步仍然到达引擎，因为它调用的
  那些回调**没有别的路**。接缝与 `POST /ai/task` 都住在**运行时**而不是某个适配器里，于是嵌入本运行时的宿主
  **不必自己再加一条**，而驱动它的那些部署**共用一份实现**、不是各写一份。

- **没有编排适配器的部署会被如实告知，而不是被拒。** `POST /ai/task` 答 `available: false` 加一个原因与下一步，
  状态 200 —— 这正是本运行时在「部署缺了某样东西」时已经在用的形状（`/auth/oauth/providers` 答空列表）。
  两个看起来更省事的答案都错：5xx 到调用方手上是「服务器内部错误」、**说不出缺了什么**，而一次空的运行会
  **声称跑过**。

- **Spring AI 适配器驱动这条接缝。** `GovernedToolCallbacks` 把运行时的工具当作回调交给模型，而那些回调**唯一的
  路径**是引擎；模型只看见 name 与 description —— 没有风险级、没有确认标记、没有撤销档。`GovernedTaskRunner`
  实现 `TaskRunner`，而一次运行会**按序**汇报每一步、带上它冲的是哪个工具，以及某个仍在等人的步骤的 token。

### 变更

- **一次运行的形状属于接缝，不属于适配器。** `TaskRun` 与它的 `Step` 从适配器搬进了运行时：一条路由拿来作答的形状，
  属于**作答的那个运行时**。对**按 0.1.2 适配器自己那份**编译过的人来说 —— 那一版几小时前才发布 —— 这是一处
  **源码级破坏**：类型没了，取列表的访问器也从 `calls()` 变成了 `steps()`。

- **这一版由测试驱动，而不只是由 demo 驱动。** 那条循环现在有一条**不需要模型**的测试 —— 一个**声明了** tool calling
  的替身，站在一个**带 tool-calling advisor** 的 client 后面 —— 于是「框架的那次调用到达引擎、而它说的话被报回运行」
  在**每次 push** 上都被检查。这两个条件不是可有可无的：不声明该能力的 stub，工具会被从请求上**摘掉**；而不带 advisor
  的 client **根本发不出它们** —— 先前那几次尝试量的正是这件事，只是当时不知道。

## [0.1.2] - 2026-10-07

**English**

### Added

- **A model can drive several governed steps.** The Spring AI adapter hands the model the runtime's
  tools and lets the framework's own calling loop sequence them, instead of asking for one plan and
  handing it back. Every step still arrives at the engine, because the callbacks the loop invokes have
  no other path — a call the gate blocks, or one waiting on a person, is reported as that rather than
  executed anyway. A run reports what it did: the answer, each step with the tool it was an attempt
  at, and the token of a step still waiting. The demo deployment exposes it as `POST /ai/task`, and
  `scripts/demo-springai-task.sh` drives it against a real model over HTTP — which is also how the
  claim was checked, since the question ("which callbacks does the framework actually invoke?") is
  about a running deployment. The tool list the framework hands the model carries no risk level,
  confirmation flag or revoke class: **measured**, not assumed.

- **A generated application can be signed into.** `POST /auth/login` and `GET /auth/me` let the
  runtime-neutral console reach its workbench against a generated application, instead of stopping at
  its own login page. Login signs in the identities `LocalIdentities` declares, and mints *the same*
  delegation token the application already verifies — same secret, same audience — so there stays one
  verification path rather than a second token format to keep in step. It is **off unless
  `KEELBASE_DEMO_PASSWORD` is injected**: no passphrase ships in the generated source or in
  `application.properties`, and with none set every attempt is refused.

### Changed

- **What this repository publishes has grown, and that is a boundary change rather than a packaging
  one.** Until now only the parent pom and `keelbase4j-protocol` reached Maven Central, so a host that
  embeds this runtime could resolve the protocol but had to build this repository locally to get the
  core and the adapter. Those two are now published alongside it: `keelbase4j-core` and
  `keelbase4j-springai` are a public API surface from this version on. The other four modules are
  deliberately **not** published: `keelbase4j-runtime` and `keelbase4j-demo` are applications, and
  `keelbase4j-generator` and `keelbase4j-mcp` are pieces a deployment composes for itself rather than
  resolves. The protocol library itself did not change in this release.

- **The response envelope can be told which packages to leave bare**, so a host whose own controllers
  answer in a shape of their own does not have this runtime's envelope wrapped around them.

### Fixed

- **The generated application's tool catalogue and chain state no longer answer anonymous callers.**
  `GET /ai/tools` and `GET /audit/verify` are now gated the way this runtime gates them; a plain user
  gets a refusal rather than a `200`.

- **A revocation in a generated application leaves a line on the audit chain**, and that chain's state
  stops at a caller this runtime knows — which is what every other governance transition there already
  did, and what the revocation path had been missing.

- **A revocation is audited under the contract's own value for it**, rather than a generic one that
  happened to be legal.

- **The adapter keeps the confirmation token with the caller, and refuses malformed tool arguments**
  instead of running the call with none.

- **The multi-step runner's conditions sit on the bean method.** On the auto-configuration class they
  were evaluated before the chat client other configurations contribute existed, so the runner was
  silently absent from a context that had everything it needed.

**中文**

### 新增

- **模型可以驱动若干受治理的步骤。** Spring AI 适配器把运行时的工具交给模型，让**框架自己的调用循环**来排步骤，
  而不是要一个计划、然后交回。每一步仍然到达引擎 —— 循环调的那些回调**没有别的路**：被闸拦下的、或在等人的调用，
  都会被**如实报告**，而不是照样执行。一次运行会汇报它做了什么：答复、每一步**冲着哪个工具**去的、以及仍在等人的
  那一个的 token。demo 部署把它作为 `POST /ai/task` 露出来，而 `scripts/demo-springai-task.sh` 用一个**真模型**
  经 HTTP 驱动它 —— 这也正是当初核对它的方式：那个问题（「框架**真的**调了哪些回调」）问的是**一个跑着的部署**。
  框架交给模型的工具列表里**没有**风险级、确认标记与撤销档：这是**量出来的**，不是想当然的。

- **生成的应用可以被登录进去。** `POST /auth/login` 与 `GET /auth/me` 让运行时中立的控制台能在一个
  生成物上走进工作台，而不是停在它自己的登录页。登录签入的是 `LocalIdentities` 声明的那些身份，并铸出
  **同一枚**本应用已经在验的委托令牌——同一 secret、同一 audience——于是始终只有一条验证路径，而不是
  多出第二种要同步保持一致的令牌格式。它**在未注入 `KEELBASE_DEMO_PASSWORD` 时是关的**：生成物源码与
  `application.properties` 都不带口令，而未设时每一次尝试都会被拒绝。

### 变更

- **本仓发布的东西变多了，而这是一次边界变更、不是打包细节。** 先前只有父 pom 与 `keelbase4j-protocol`
  会到 Maven Central，于是**嵌入本运行时的宿主**能解析协议，却必须先把本仓在本地构建一遍才拿得到 core 与适配器。
  这两个现在与它一同发布：从本版起，`keelbase4j-core` 与 `keelbase4j-springai` 是**对外的 API 面**。
  其余四个模块**有意不发布**：`keelbase4j-runtime` 与 `keelbase4j-demo` 是**应用**，而
  `keelbase4j-generator` 与 `keelbase4j-mcp` 是**部署自己拼进去的部件**，不是从仓库里解析来的。
  协议库本身在这一版没有变化。

- **响应信封可以被声明「哪些包原样作答」**，于是一个自带控制器、按自己的形状作答的宿主，不会发现自己的正文被套上
  本运行时的信封。

### 修复

- **生成物的工具名录与链状态不再答匿名调用者。** `GET /ai/tools` 与 `GET /audit/verify` 现在按本运行时的方式加闸；
  普通用户拿到的是拒绝，而不是 `200`。

- **生成物里的撤销在审计链上留一行**，且那条链的状态停在**本运行时认识的调用者**那里 —— 那是那里**每一次别的治理迁移**
  早就在做的事，而撤销那条路一直缺着它。

- **撤销按契约给它自己的取值留痕**，而不是用一个**合法但泛化**的取值。

- **适配器把确认令牌留在调用方那一侧，并拒掉形状不对的工具参数**，而不是拿空参数去跑那次调用。

- **多步 runner 的条件放在 bean 方法上。** 挂在自动配置**类**上时，它们的求值早于别的配置贡献的 chat client 存在，
  于是 runner 在一个**什么都不缺**的上下文里**一声不响地缺席**。

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
  the `release` profile of their own poms. **[Corrected 2026-10-07: that last clause is not true —
  no module has ever declared `maven.deploy.skip` in its own pom (`git grep deploy.skip v0.1.0` finds
  only a comment). What publishes has always been the *reactor*, and 0.1.2's `release.yml` header now
  says so. The original wording is kept above.]**
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
  在各自 pom 的 `release` profile 里声明不发布。**[2026-10-07 更正：这后半句不成立 ——
  **没有任何模块**在自己的 pom 里声明过 `maven.deploy.skip`（`git grep deploy.skip v0.1.0` 只剩一条注释）。
  发布什么一直是由 **reactor** 决定的，0.1.2 的 `release.yml` 头注已按实况写明。原文保留在上。]**
- 运行时的会话存储是 **transcript，不是 memory**：没有嵌入、没有检索、没有记忆策略。

[Unreleased]: https://github.com/rain6fish/KeelBase4J/compare/v0.1.3...HEAD
[0.1.3]: https://github.com/rain6fish/KeelBase4J/releases/tag/v0.1.3
[0.1.2]: https://github.com/rain6fish/KeelBase4J/releases/tag/v0.1.2
[0.1.1]: https://github.com/rain6fish/KeelBase4J/releases/tag/v0.1.1
[0.1.0]: https://github.com/rain6fish/KeelBase4J/releases/tag/v0.1.0
