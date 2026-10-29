# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This repository is a conformance implementation, so its version describes *this implementation* — not
the protocol. The protocol is frozen in its own repository (`rain6fish/keelbase-contract`) and is cited
by contract version; a number in this file never renames or re-versions a contract.

Each version is written in two blocks — English first, then Chinese — marked as such.

## [0.1.15] - 2026-10-29

**English**

### Added

- **A conversation can be named, and the list says how long it is.** Conversations in this runtime are
  derived from their messages, so a name had nowhere to live; `conversation-data` v2 has carried an optional
  `title` since the contract was written. `V17__conversation_titles` adds one row per named conversation, so
  "no name" has exactly one representation (no row), and `PUT /ai/conversations/{id}/title` names it — owner
  only, by the same rule the trace uses; a blank title clears the name, and one over 200 characters is refused
  rather than truncated, because a silently shortened name would look like the one the user typed. The list
  and the trace now carry `title` and `messageCount`; the count is the whole transcript's, because the list
  carries only the newest two turns and `messages.length` would say "2" for every conversation past its first
  exchange.

### Changed

- **The version is `0.1.15`.** All eight poms finish at `0.1.15`; the published set is unchanged: the parent
  pom, `keelbase4j-protocol`, `keelbase4j-core` and `keelbase4j-springai`.

**中文**

### 新增

- **会话可以起名，列表也会说出它有多长。** 本运行时的会话由消息推出来，名字没有地方可放；`conversation-data`
  v2 从写下契约起就带着可选的 `title`。`V17__conversation_titles` 一条命名会话一行，故「没有名字」只有一种
  表示（没有这一行）；`PUT /ai/conversations/{id}/title` 给它起名 —— 仅属主，与追踪同一条规则；空白标题
  清除名字，超过 200 字**拒绝**而不是截断，因为被悄悄截短的名字看起来会与用户写下的那个一样。列表与追踪
  现在都带 `title` 与 `messageCount`；条数是**整段**对话记录的，因为列表只带最新两条回合，`messages.length`
  会对每一条超过一回合的会话都说「2」。

### 变更

- **版本为 `0.1.15`。** 八个 pom 最终落到 `0.1.15`；发布集合不变：父 pom、`keelbase4j-protocol`、
  `keelbase4j-core` 与 `keelbase4j-springai`。

## [0.1.14] - 2026-10-28

**English**

### Added

- **The release gate exercises the signing-key upload instead of describing it.**
  `scripts/publish-signing-key.sh --self-test` drives a deliberately hanging fake `gpg` and prints, per
  scenario, the exit status, the elapsed time and the keyservers actually tried. The gate runs it, and
  separately checks that `release.yml` still calls the script rather than sending with a bare
  `gpg --send-keys`: a script nobody calls protects nothing, and a self-test nobody runs stops being true.

### Fixed

- **The signing-key upload fails in minutes, not after six hours.** The step ran two bare
  `gpg --send-keys` calls with no bound. On 2026-10-26 `keyserver.ubuntu.com` stopped answering mid-send,
  the job ran until GitHub's six-hour cap cancelled it, and Central received nothing; the same tag had to
  be published on a re-run. Each attempt now carries an outer `timeout`, is retried with backoff, and
  falls back to a second keyserver Central reads. The step is capped at ten minutes and the job at
  sixty. If every server fails *and* the key is retrievable nowhere, the step stops with the next step
  spelled out, rather than deploying signatures Central cannot verify. The self-test's time caps follow
  the timeout's kill grace (`KEYSERVER_KILL_AFTER`), so they mean the same thing on a host that cannot
  deliver SIGTERM into the child process.

- **The local-debug demo runs the bootable jar, not the javadoc one.** The release profile writes
  `-javadoc.jar` and `-sources.jar` beside the bootable jar, and their names sort first, so the demo
  handed `java -jar` a jar with no `Main-Class` and the gate went red on a tree whose code was fine.

### Changed

- **Release notes are checked against the tag's commits.** `CLAUDE.md` now requires comparing
  `git log --oneline <previous tag>..HEAD` with the tag body and that version's `CHANGELOG.md` entry
  before a `v*` tag is cut: `0.1.11` shipped the execution↔trace linking and its notes omitted it, and a
  published tag cannot be re-cut. The `0.1.11` post-release note now says the tag object `1b038a2`
  points at commit `ffea517`, so it no longer reads like a commit range.

- **The version is `0.1.14`.** All eight poms move from `0.1.13` to `0.1.14`; the published set is
  unchanged: the parent pom, `keelbase4j-protocol`, `keelbase4j-core` and `keelbase4j-springai`.

**中文**

### 新增

- **发布门禁实际跑一遍签名公钥上传，而不是只在文字里描述它。**
  `scripts/publish-signing-key.sh --self-test` 驱动一个故意挂起的假 `gpg`，逐场景打印退出码、耗时与真正
  试过的 keyserver。门禁会跑它，另外还会检查 `release.yml` 是否仍在调用这个脚本、而不是退回裸的
  `gpg --send-keys`：没人调用的脚本保护不了任何东西，没人跑的自检也会慢慢不再成立。

### 修复

- **签名公钥上传几分钟内失败，不再挂满六小时。** 那一步原先跑两条没有界的裸 `gpg --send-keys`。
  2026-10-26 `keyserver.ubuntu.com` 发到一半不再回应，作业一直跑到 GitHub 六小时上限被取消，Central 什么
  都没收到，同一 tag 只能重跑才发出去。现在每次尝试外面都套一层 `timeout`，带退避重试，失败就换一台
  Central 也认的 keyserver。该步骤封顶十分钟、整个作业封顶六十分钟。如果所有服务器都失败**且**这把钥匙
  哪台都查不到，这一步就停下并把下一步写清楚，而不是把一批 Central 验不了签名的构件发上去。自检的时间
  上限跟着 `timeout` 的击杀宽限（`KEYSERVER_KILL_AFTER`）走，于是在无法把 SIGTERM 投递进子进程的主机上，
  它们仍然说同一件事。

- **本地调试 demo 跑的是可执行 jar，不是 javadoc 包。** 发布档会在可执行 jar 旁边写出 `-javadoc.jar` 与
  `-sources.jar`，它们的名字排在前面，于是 demo 把一个没有 `Main-Class` 的包交给 `java -jar`，门禁在一棵
  代码没问题的树上判红。

### 变更

- **发布说明对照 tag 实际包含的提交。** `CLAUDE.md` 现在要求在打 `v*` tag 之前，把
  `git log --oneline <上一 tag>..HEAD` 与该版本 tag 正文、`CHANGELOG.md` 条目逐条比对：`0.1.11` 就带着
  execution↔trace 关联而说明里没有它，而已发布的 tag 重打不了。`0.1.11` 的发布后补记也改为「tag 对象
  `1b038a2` 指向提交 `ffea517`」，不再读起来像一个提交区间。

- **版本为 `0.1.14`。** 八个 pom 由 `0.1.13` 落到 `0.1.14`；发布集合不变：父 pom、`keelbase4j-protocol`、
  `keelbase4j-core` 与 `keelbase4j-springai`。

## [0.1.13] - 2026-10-26

**English**

### Added

- **A host can register tools after startup, and the registry remembers where each one came from.**
  `ToolRegistry.register(AiTool, Origin)` is public, idempotent and synchronized, and it keeps the one
  declaration rule: a governed write without a `resultType` is still refused. `Origin.DEPLOYMENT` is a
  Spring `AiTool` bean the deployment wrote; `Origin.HOST_ASSEMBLED` is a tool a host registers while it
  assembles a turn (an MCP server's tool, a SKILL's shell). `all()` still answers the engine, the policy
  store, the inventory and the confirmation views; the new `catalogue()` answers the two model faces, so
  a host-assembled tool cannot be offered to every agent as if it were the deployment's own. A
  host-assembled tool cannot shadow a deployment tool of the same name.

- **`GovernancePolicyStore.ensureDisabled(names)`.** It adds `{enabled:false}` for tools that have no
  override yet, through the same validation and history path under the same lock. `set` replaces the
  whole document and would race a policy console; this only adds the missing entries and never touches
  an override a person wrote. It is the gate a host-assembled external tool starts behind.

### Changed

- **`SpringAiToolCallPlanner` reads `catalogue()`.** A tool a host assembled for one agent is not
  plannable for every caller of `/ai/chat`.

- **`/keelbase/status` counts R2 and above as writes.** An external tool that declares no `resultType`
  is an action surface, not a read, so the reading no longer under-reports it.

- **The version is `0.1.13`.** All eight poms move from `0.1.12` to `0.1.13`; the published set remains
  the parent pom, `keelbase4j-protocol`, `keelbase4j-core` and `keelbase4j-springai`.

**中文**

### 新增

- **宿主可以在启动之后注册工具，注册表也记得每个工具来自哪里。** `ToolRegistry.register(AiTool, Origin)`
  公开、幂等、加锁，并保留唯一那条声明规则：受治理的写没有 `resultType` 仍然被拒。`Origin.DEPLOYMENT`
  是部署自己写的 Spring `AiTool` bean；`Origin.HOST_ASSEMBLED` 是宿主在装配一轮会话时注册的工具（某个
  MCP 服务的工具、某个 SKILL 的 shell）。`all()` 继续回答引擎、策略库、清单与确认视图；新增的
  `catalogue()` 回答两个模型面，于是宿主装配的工具不会被当成部署自己的目录发给每个 agent。宿主装配的
  工具不能顶替同名的部署工具。

- **`GovernancePolicyStore.ensureDisabled(names)`。** 它为还没有覆盖的工具补 `{enabled:false}`，走同一套
  校验与历史路径、同一把锁。`set` 会替换整份文档并与策略控制台撞车；这个方法只补缺失的条目，绝不改动
  人写下的覆盖。它正是宿主装配的外部工具一开始所在的那道闸。

### 变更

- **`SpringAiToolCallPlanner` 改读 `catalogue()`。** 宿主为某一个 agent 装配的工具，不是 `/ai/chat`
  每个调用方都能规划的东西。

- **`/keelbase/status` 把 R2 及以上计为写。** 不声明 `resultType` 的外部工具是动作面、不是只读，读数
  不再少报它。

- **版本为 `0.1.13`。** 八个 pom 由 `0.1.12` 落到 `0.1.13`；发布集合仍是父 pom、`keelbase4j-protocol`、
  `keelbase4j-core` 与 `keelbase4j-springai`。

## [0.1.12] - 2026-10-26

**English**

### Fixed

- **A row denial now names the subject and the action it refused.** `ruleDeniedGuidance` wrote the whole
  `explanation` only when the refusal claimed a deny basis, so a row denial — which deliberately claims
  none, because the frozen `permission-decision` defines none for it — dropped the subject and action the
  guard had already computed: the exception carried them, the wire did not. The guidance is now written
  whenever there is anything to explain, and a row denial emits `{"reason", "subject", "action"}` with
  `deniedBy` absent. That absence is the statement — no basis is claimed — and the shape stays inside
  `error-body.schema.json`, which types `explanation` as `["object", "string"]` with no inner schema.

**中文**

### 修复

- **行拒绝现在点名它拒了哪个主体、哪个动作。** `ruleDeniedGuidance` 只在拒绝**声称了依据**时才写整段
  `explanation`，于是行拒绝——它**刻意不声称**依据，因为冻结的 `permission-decision` 不为它定义——把守卫
  已经算出来的 subject 与 action 丢掉了：异常带着它们，线缆上没有。现在只要有可解释的东西就写，行拒绝交出
  `{"reason", "subject", "action"}` 而 `deniedBy` 缺席。那个缺席本身就是那句话——不声称依据——且形状仍在
  `error-body.schema.json` 之内：它把 `explanation` 定为 `["object", "string"]`、内部无 schema。

## [0.1.11] - 2026-10-23

**English**

### Added

- **`permissions.adminOnly` — a tool can declare that it serves administrators only.** The declaration
  is opt-in and defaults to `false`. A non-administrator calling such a tool is refused at the gate,
  before any confirmation exists, as the frozen `admin_only` check; the tool inventory reports
  `{"adminOnly": true}` for it and `null` for a tool that declares nothing, rather than inventing a
  permission the tool never declared.

### Changed

- **A confirmed write keeps its `authorization_basis`.** The executed row of a write that arrived
  through a confirmation used to carry no basis: only the ALLOW path recorded the gate's structured
  reasons, so an approved write left "why was this allowed" unanswered on the row that actually wrote.
  The execution tail now records the same snapshot, and the idempotent-reuse branch does too. The basis
  stays beside the hash chain, never inside its payload.

- **The version is `0.1.11`.** All eight poms move from `0.1.10` to `0.1.11`; the published set remains
  the parent pom, `keelbase4j-protocol`, `keelbase4j-core` and `keelbase4j-springai`.

### Post-release note

- **`0.1.11` also carries the execution↔trace linking, which this section had not said.** Commit
  `ffea517` — "feat(audit): pair each execution with the host trace id" — is inside the `v0.1.11` tag
  (the tag object `1b038a2` points at commit `ffea517`). Every governed attempt now writes one
  `execution_trace_links` row, keyed by the `executionId` the audit and effect rows already carry, in a
  separate best-effort transaction, and records the host's trace id or null when the host sets none.
  The tag's own message does not mention this either. The tag stays as published; this note only records
  what the artifact contains.

**中文**

### 新增

- **`permissions.adminOnly` —— 工具可以声明自己只服务管理员。** 该声明是选择加入的，默认 `false`。
  非管理员调用这类工具时，会在闸门、在任何确认行出现之前被拒，理由是冻结的 `admin_only` 检查项；
  工具清单对它报 `{"adminOnly": true}`，对什么都没声明的工具报 `null`，而不是凭空编一项它从未声明的权限。

### 变更

- **经确认路径的写保留 `authorization_basis`。** 经确认路径执行的那一次写，落下的结果行过去不带依据：
  只有 ALLOW 路径记录闸门的结构化理由，于是被批准的写让「凭什么放它」在真正写了东西的那一行上没有答案。
  执行尾段现在记下同一份快照，幂等复用分支也一样。依据仍存在哈希链旁边，从不进入链的载荷。

- **版本为 `0.1.11`。** 八个 pom 由 `0.1.10` 落到 `0.1.11`；发布集合仍是父 pom、`keelbase4j-protocol`、
  `keelbase4j-core` 与 `keelbase4j-springai`。

### 发布后补记

- **`0.1.11` 还带着 execution↔trace 关联，而本节此前没有写。** 提交 `ffea517`（`feat(audit)`：为每次
  执行记录宿主 trace 关联）就在 `v0.1.11` tag 之内（tag 对象 `1b038a2` 指向提交 `ffea517`）：每次受治理
  尝试现在写一行 `execution_trace_links`，键是审计行与副作用行已经携带的 `executionId`，走独立的事务尽力
  写，并记下宿主的 trace id、宿主没有时存 null。tag 自己的正文也没提这一条。tag 保持已发布状态不动；
  本补记只记录发布产物实际包含什么。

## [0.1.10] - 2026-10-21

**English**

### Added

- **`GET /keelbase/status` — the integration-health report.** Four dimensions, each with its own
  status and the reading behind it: registered tools (total, read/write, risk distribution, invalid
  declarations), the effective R3/R4 gate read from the same `GovernanceService.decide` call the
  execution engine makes, audit rows (count, last-24h count, latest time and id, chain verification),
  and a local delegation sign-then-verify probe kept separate from the inbound token. A dimension that
  cannot be measured reports `unknown` or `not_applicable` rather than green. Authenticated, and
  guarded by `read` on `IntegrationStatus`.

- **`GET /audit/coverage` — the host answers its own coverage statement.** The statement is assembled
  from the vendored gap registry and the rows in the requested window: it declares the registry
  revision, the sources actually read, a derived verdict and the applicable gaps, and it is gated by
  `read` on `AuditLog`, so an audit reader reaches it and an ordinary caller does not. Deployment
  facts (`host`, whether the host owns the business write, whether it has an AI path of its own) are
  declared under `keelbase.coverage.*`; the registry is a build-time mirror of
  `conformance/coverage-gaps.json`, never a second source.

- **A PM reference project on the same governance chain.** `PmProject` and `PmTask` with four tools at
  R1/R3/R4/R5 use the same gate, audit chain and local compensation as the CRM sample, with a
  one-command demo of 36 assertions and schema V15 for H2, MySQL and PostgreSQL.

- **An explicit offline local-debug mode.** `keelbase.local-debug.enabled` (default off) lets a
  deployment that carries a provider start without a model key; without the switch the same
  deployment still fails on the missing key.

### Changed

- **Both read projections carry `executionId`**, so a client pairs an effect with the audit row of the
  same execution instead of matching tool, user and time; the vendored snapshot moves to contract
  `v1.14.0`, and the trace effect step emits the `status` that v3 requires.
- **The wire conformance check compares `type`**, not only presence.
- **`tool_start` sends `presentationText`** (`{key, fallback}`) rather than a bare string.
- **A read-only reader gets no caller linkage**: items omit `userId` and `conversationId`, while the
  administrator's 19-key projection and an ordinary caller's own list are unchanged.
- **The three refusals say what happened, why, and what to do**: the 401 distinguishes five causes
  inside the frozen error body's existing keys, the 403 names the action and subject it refused, and
  `keelbase/status` carries structured diagnostics (dimension, severity, code, subject, what, why,
  nextStep) for every finding.
- **Only a missing after-snapshot counts as an uncaptured change**, so a create is not reported as a
  false coverage gap.
- **Integration health no longer warns about an R5 tool's missing compensation**: a write that can
  never run has no side effect to compensate.
- **The release workflow checks out the contract tag the snapshot was built from** (`v1.14.0`), the
  same pin `ci.yml` already carried, so a published tag verifies against the contract CI checks.

### Fixed

- **A governed write that declares no `resultType` is refused registration.** R3/R4/R5 tools (or any
  tool overriding `requiresConfirmation`) are no longer registered as reads: they are not offered to
  a model and cannot be executed, the refusal is logged at startup, `keelbase/status` names it under
  `missingResultTypes`, and `ToolRegistry.require` returns the same diagnosis. R0–R2 reads are
  unaffected.

**中文**

### 新增

- **`GET /keelbase/status` —— 接入健康度报告。** 四个维度，每个维度带自己的状态与状态背后的读数：
  已注册工具（总数、读/写、风险分布、非法声明）、从执行引擎所做的同一次 `GovernanceService.decide`
  调用读出的生效 R3/R4 门控、审计行（条数、近 24 小时条数、最近时刻与 id、链校验），以及与入站令牌
  **分开**记录的本地委托签发/验签探针。测不到的维度报 `unknown` 或 `not_applicable`，而不是绿色。
  已认证，由 `IntegrationStatus` 上的 `read` 把关。

- **`GET /audit/coverage` —— 宿主能答出自己的覆盖声明。** 声明由 vendored 的缺口登记表与时间窗内的行
  组装：给出登记表版本、真的读过的源、算出的结论与适用的缺口；闸是 `AuditLog` 上的 `read`，故审计
  读者够得到、普通调用方够不到。部署事实（`host`、宿主是否拥有业务写、是否有自己的一条 AI 路径）在
  `keelbase.coverage.*` 下声明；登记表是 `conformance/coverage-gaps.json` 的构建期镜像，不是第二份源。

- **PM 参考项目走同一条治理链。** `PmProject` 与 `PmTask` 的四个工具分处 R1/R3/R4/R5，闸、审计链与
  本地补偿都与 CRM 样例同一条；附一条命令的演示（36 项断言）与 H2 / MySQL / PostgreSQL 三种方言的
  schema V15。

- **显式的离线本地调试模式。** `keelbase.local-debug.enabled`（默认关）让带着 provider 的部署在没有
  模型 key 时也能启动；去掉开关后，同一个部署仍会因缺 key 失败。

### 变更

- **两个读投影都发出 `executionId`**，客户端可把一条副作用与同一次执行的审计行精确配对，而不必按
  工具、用户和时间去凑；vendored 快照移到契约 `v1.14.0`，轨迹 effect 步也发出 v3 所要求的 `status`。
- **线上形态一致性检查开始比较 `type`**，不再只看字段在不在。
- **`tool_start` 改发 `presentationText`**（`{key, fallback}`），不再发裸字符串。
- **只读读者不再收到调用方关联**：每一项去掉 `userId` 与 `conversationId`；管理员的 19 键投影与
  普通调用方本人清单保持不变。
- **三类拒绝说清发生了什么、为什么、怎么办**：401 在冻结错误体既有的键里区分五种原因，403 点名被拒
  的动作与主体，`keelbase/status` 的每条发现都带结构化诊断（dimension、severity、code、subject、
  what、why、nextStep）。
- **只有缺 after 快照才算变更未被捕获**，故新建不会被报成假覆盖缺口。
- **接入健康度不再为 R5 工具缺补偿告警**：一次永远跑不了的写，没有会补偿的副作用。
- **发布工作流检出的契约 tag 与快照所依据的一致**（`v1.14.0`），与 `ci.yml` 早已携带的 pin 相同，
  故发出去的 tag 校验的是 CI 校验的那份契约。

### 修复

- **没有声明 `resultType` 的受治理写被拒绝注册。** R3/R4/R5 工具（或任何覆写
  `requiresConfirmation` 的工具）不再被登记成只读：它们不会被交给模型、也无法执行，启动日志记下
  拒绝，`keelbase/status` 在 `missingResultTypes` 里点名，`ToolRegistry.require` 交回同一份诊断。
  R0–R2 的读不受影响。

## [0.1.9] - 2026-10-18

**English**

### Fixed

- **A generated application's confirmation card says `approval` when a second person is required.**
  The pending outcome now carries `requiresApproval`, and the stream derives the mode from it — the
  initiator no longer sees a self-confirm card that the application then refuses with a 403.

- **The intent row carries the authorization basis.** The result row is best-effort; if its append
  fails, the basis is no longer lost with it.

- **A successful sign-in is attributed in the operations chain.** The generated login publishes the
  authenticated identity into the interceptor's attribute, so the `LOGIN` row names the operator
  instead of nobody.

- **`execution_id` is indexed in all three dialects**, so the in-database joins the key exists for do
  not degrade into a full scan.

**中文**

### 修复

- **生成物的确认卡在需要第二个人时显示 `approval`。** 待决结果现在带上 `requiresApproval`，流式从它
  取模式 —— 发起者不再看到一张随后被应用 403 拒绝的「自己确认」卡。

- **intent 行带上授权依据。** 结果行是尽力而为；它写失败时，依据不再随之丢失。

- **成功的登录在操作链里有归属。** 生成物的登录把认证后的身份写进拦截器的属性，`LOGIN` 行因此说得清
  操作者是谁，而不是空着。

- **`execution_id` 在三种方言里都建了索引**，这个键存在的目的（库内 join）不再退化成全表扫描。

## [0.1.8] - 2026-10-18

**English**

### Added

- **A generated application now carries its own operations checklist, and says which half of its state a
  restart destroys.** The README the generator emits gained an `## Operations` section — what survives
  (the business tables and `flyway_schema_history`), what does not (the audit chain, the pending
  confirmations and the side-effect records, which is where a revocation is remembered, are held in
  memory), how to point the application at PostgreSQL, the secrets whose shipped values are spike
  values, a `pg_dump` of the durable half, and an upgrade that is an additive migration with
  `ddl-auto=validate` failing the boot on a mismatch. The volatile half is declared **demo-grade on
  purpose** rather than left to read as an oversight: it is a deliberate simplification, and moving that
  state into its own tables is the change a deployment that leans on it should make.

- **The governance policy answers on the wire, and what it says is enforced.** The frozen
  `governance-policy` object is now served at `GET` / `PUT /api/v1/ai/governance/policy` — writable by
  administrators, readable by administrators or an audit reader: the per-tool overrides and the audit
  granularity, plus the object's own `updatedAt` and `revision`. Writing it changes what happens next rather than only what a console shows — a disabled
  tool and a role allowlist refuse at the gate the engine runs every call through, a gate mode moves it,
  and the granularity governs the AI audit chain — because a policy that is stored and ignored is the
  failure the surface exists to avoid. The store refuses a submission it cannot apply (a tool this
  deployment does not register, a mode outside the frozen three, a role outside its vocabulary, an
  unknown key) with a `400` and the reason, in whole rather than field by field. `revision` is the
  frozen object's first-twelve-hex content fingerprint, so the same policy submitted twice fingerprints
  the same. The `ai-tool-inventory`'s `enabled` and `allowedRoles` now read from this same policy, so the
  inventory and the gate cannot disagree.

- **A generated application's tool catalogue answers the frozen `ai-tool-inventory` too.** The
  generator's emitted `GET /ai/tools` answered a hand-rolled `{name, riskLevel}`; it now answers the
  frozen object field for field, the same shape the runtime serves — so a console reading the runtime
  reads a generated application unchanged. Its governance half is asked of the gate the generated engine
  itself runs, never restated, so the write tool is listed as the one the gate confirms (its `R3`
  deriving `riskStrategy: "confirmation"`) and the read tool as one that runs by itself. What a
  revocation would be is read off what the tool writes — a tool that writes a row is undone locally, one
  that writes nothing has nothing to revoke — and the two dimensions this application declares nowhere
  (`parameters`, `permissions`) answer empty and `null` rather than an invented value. The generated
  `AiTool` gained the `description()` it was missing, since the object requires one.

- **A conversation's decision trace now answers its governance half, not only its turns.** The
  conversation a call was made in is recorded where the call is made — on the audit row, on the
  confirmation row that carries the proposal from proposal time to decision time, and on the side-effect
  row the write produced — so `GET /api/v1/ai/conversations/{id}/trace` merges the turns with the
  `tool_call`, `confirmation` and `effect` steps in the order they happened, rather than answering the
  turns and stating that the rest cannot be placed. Three nullable columns and an additive migration
  (`V11`) carry the link; a row written with no conversation (a task run, an MCP caller) carries null and
  appears in no trace, which is what "no conversation" honestly is. Nothing already written changes, and
  the audit hash chain — over `{action, userId, detail}` — still verifies, because the link is stored
  beside it.

- **A generated application carries the operations chain too — a second audit, of the business writes
  themselves.** The generated `GET /audit/operations/logs` answers the frozen `operation-audit-log-row`
  (administrators only, `{total, page, limit, items}`, newest first), and it reads a chain the AI one is
  not: its rows are the business writes, including the ones no AI took part in. A request interceptor
  records a write method on a business path after the handler has answered — so the row can say how the
  request ended — while reading the identity before that, because a request's security context is gone by
  then; a request that carried no identity is recorded with nobody attached rather than dropped, and the
  AI surface is left out because its calls have the chain next door. Failing to write a row never fails
  the request. The rows are a table (`operation_audit_logs`, migration `V3`), so unlike the AI chain they
  survive a restart — which the generated README's "where the state lives" table now says, and it names
  what it lists as in-memory the **AI** audit chain, since that is what it is.

- **A generated application's decision trace answers its governance half too.** The generated
  `GET /ai/conversations` lists the caller's own conversations, and
  `GET /ai/conversations/{id}/trace` answers `{conversation, steps}` — the turns plus the governance
  events those turns produced, merged into one timeline in the order they happened. The conversation id
  is recorded beside every row the engine writes: on the audit row, on the pending record that carries
  the proposal from proposal time to decision time, and on the effect the approval produces — so a write
  approved in a request of its own still names the conversation that proposed it. A row written with no
  conversation (a task run) carries null and appears in no trace, which is what "no conversation"
  honestly is. The effect list's `conversationId` is no longer hard-coded null, and the generated
  README no longer says it is.

- **A generated application serves the governance policy too, and enforces it.** The generated
  `GET` / `PUT /ai/governance/policy` answers the frozen `governance-policy` — administrators only —
  with the per-tool overrides and the audit granularity plus the object's own `updatedAt` and
  `revision`, and what a GET returns is also a valid PUT body. What it says is enforced on the gate the
  engine runs every call through: a disabled tool and a role allowlist refuse, an explicit gate mode
  moves the gate, and the granularity governs the AI audit chain (`off` writes no rows). The store
  refuses a submission it cannot apply — an unregistered tool, a mode outside the frozen three, a role
  outside this application's vocabulary, an unknown key — with a 400 and the reason, in whole rather
  than field by field. The catalogue's `enabled` and `allowedRoles` read from this same policy, so the
  list and the gate cannot disagree, and `revision` is the frozen object's first-twelve-hex content
  fingerprint taken over ordered content.

- **An execution id links the three rows one attempt leaves.** Every attempt mints one `execution_id`
  and writes it on the intent row, the result row and the side-effect row; `V14` adds the nullable
  column to `ai_audit_logs` and `side_effects` in all three dialects, beside the hash chain rather
  than inside it, so existing chains still verify. The id names an attempt, not a conversation or an
  effect: the idempotent-reuse branch writes its own intent row and deliberately adds no effect row,
  because nothing was written there.

- **A side-effect row says who it was for.** `EffectController.view()` now carries `userId`, the
  caller the write was made for. The column has been non-null since it was added; only the wire field
  was missing. The vendored snapshot moves to `side-effect-revoke` v5, which declares the field
  optional, so v4-shaped payloads stay valid while this runtime always sends it.

- **The governance read faces answer to the capability seam.** The four governance read endpoints call
  `OwnershipGuard.requireAction` with per-surface subjects instead of inlining `isManager()`, and the
  policy write keeps its write action. The side-effect list gains a global read-only branch that reuses
  the manager's exact projection, and a read-only confirmation queue (`GET /ai/confirmations`) is added
  on the same seam. Administrators still see everything, ordinary callers are still refused, and an
  audit reader is granted reads only, so it cannot approve or execute by construction.

- **The chat request's structured slot is generic, and the old name still binds.** `customerId` becomes
  `targetId`; the old name is kept as a JSON alias and behind a deprecated accessor, because callers
  outside this repository still post it. The context key the routes read follows the new name.

- **The trail keeps what a write changed and what allowed it.** `side_effects` gains `before_snapshot`,
  and `ai_audit_logs` gains the authorization basis — the frozen reasons a call was allowed or refused
  — carried on the row rather than only inside a sentence. Neither field needs a contract change; both
  are already declared.

- **The tool inventory answers the frozen object, and its governance half is asked rather than
  restated.** `GET /ai/tools` answers `ai-tool-inventory` for every registered tool, administrators
  only; `gateMode` and its two derived flags come from the same `GovernanceService.decide` call the
  engine makes, so a tool cannot be described one way and gated another.

### Fixed

- **A high-impact action in a generated application needs a second person.** The generated engine
  treated `REQUIRE_APPROVAL` like an ordinary confirmation, so an R4 tool or a policy
  `mode: "approval"` could be approved by its own initiator — a declared control turned into one
  click. The pending row now carries the mode, and the claim refuses self-approval while leaving
  decline open to whoever holds the token, as the runtime does.

- **An own-scoped read on the side-effect ledger no longer widens to everyone's rows.** The list
  treated any `read` grant as global; a tier-B rule with an owner field now stays narrowed to the
  caller's own rows. The global branch requires the capability's scope to be `all`.

- **An over-long `User-Agent` can no longer suppress an operations-audit row.** Client-controlled
  values are truncated to their column widths before the insert, so the row is recorded rather than
  lost when the value would have overflowed.

- **An unchanged policy no longer appends a duplicate history snapshot.** The store compares the
  content revision with the latest snapshot before saving.

- **The operations-audit row's `userId` matches the frozen object.** `userId` is `number|null` there,
  so a numeric identity is emitted as a number and any other identity as null, with the string carried
  in `username` instead of dropped. The generated application emits the same shape.

**中文**

### 新增

- **生成物现在自带运维清单，并说清它的状态有哪一半活不过一次重启。** 生成器发出的 `README` 多了一节 `## Operations` —— 什么留得下（业务表与 `flyway_schema_history`）、什么留不下（**审计链**、待裁决的确认、以及副作用记录 —— **撤销就记在那里** —— **都在内存**）、怎么把它指向 PostgreSQL、哪些密钥的出厂值是 spike 值、对**持久那一半**做 `pg_dump`，以及「升级就是一条加性迁移、`ddl-auto=validate` 在不一致时让启动失败」。**活不过重启的那一半被声明为「刻意 demo 级」**，而不是留给读者当成疏漏：它是**刻意的简化**，而把这份状态搬进它自己的表，才是**真的依赖它的部署**该做的改动。

- **治理策略在线缆上答得出来，而它说的东西是被施加的。** 冻结的 `governance-policy` 对象现在服务在 `GET` / `PUT /api/v1/ai/governance/policy` —— **写由管理员、读由管理员或审计读者**：逐工具的覆盖与审计粒度，外加对象自带的 `updatedAt` 与 `revision`。写下它改变的是**接下来会发生什么**、不只是控制台显示什么 —— 被禁用的工具与角色白名单在引擎每次调用都要过的**那道闸**上拒绝、门控档位把闸移动、粒度管的是 AI 审计链 —— 因为**被存下却被忽略**的策略正是这条面生来要避免的失败。存储**拒绝**它施加不了的提交（本部署没有注册的工具、冻结三值之外的 `mode`、本部署词表之外的角色、未识别的键），回 `400` 并给出理由，且是**整份**拒绝、不是逐字段半应用。`revision` 是冻结对象写明的**前十二位十六进制内容指纹**，故同一份策略提交两次指纹相同。工具清单的 `enabled` 与 `allowedRoles` 现在读自同一份策略，故清单与那道闸不可能各执一词。

- **生成物的工具名录也答冻结的 `ai-tool-inventory` 了。** 生成器发出的 `GET /ai/tools` 原本答的是手搓的 `{name, riskLevel}`；现在按冻结对象**逐字段**作答，与运行时服务的是**同一个形状** —— 故读运行时的控制台**原样**读生成物。它的治理那一半向**生成物引擎自己用的那道闸**问来、从不复述：写工具列成闸会确认的那个（它的 `R3` 推出 `riskStrategy: "confirmation"`）、读工具列成自己就跑的那个。撤销会是什么，从**它写什么**读出 —— 写下一行的工具靠**本地**撤销、什么都不写的**没有可撤的** —— 而本应用**哪里都没声明**的两个维度（`parameters`、`permissions`）答**空**与 `null`、不是编一个。生成物的 `AiTool` 补上了它缺的 `description()`，因为对象要求这一项。

- **一条会话的决策轨迹现在答得出它的治理那一半，不只是回合。** 一次调用所在的**那条会话**，就在**调用发生的地方**被记下 —— 记在审计行上、记在把提议从「提议时刻」带到「裁决时刻」的那条确认行上、也记在那次写产出的副作用行上 —— 于是 `GET /api/v1/ai/conversations/{id}/trace` 把回合与 `tool_call` / `confirmation` / `effect` 三种步**按发生先后**并成一条时间线，而不是只答回合、再说其余放不进去。三个**可空**列与一条**加性**迁移（`V11`）承载这条链；**没有会话**时写下的行（一次任务运行、一个 MCP 调用方）带 null、也不出现在任何追踪里 —— 而「没有会话」本来就该如此。已经写下的一切都不变，审计哈希链（取自 `{action, userId, detail}`）仍验得过，因为这条链**存在链旁边**。

- **生成物也带上操作链了 —— 第二条审计，记的是业务写本身。** 生成物发出的 `GET /audit/operations/logs` 按冻结的 `operation-audit-log-row` 作答（仅管理员 · `{total, page, limit, items}` · 最新在前），而它读的这条链**不是** AI 那条：它的行是**业务写本身**，包括**没有 AI 参与**的那些。一个请求拦截器在**业务路径的写方法**上、于处理器**答完之后**记下一行 —— 所以那一行说得出这次请求**怎么结束的** —— 而**身份在之前**读，因为到那时请求的安全上下文已经没了；**没带身份**的请求被记下、只是**不挂人**，而不是丢掉；**AI 面被排除**，因为它的调用有隔壁那条链。**写不进这一行不会让请求失败**。这些行是一张**表**（`operation_audit_logs`、迁移 `V3`），故与 AI 链不同、**活得过重启** —— 生成物 README 的「状态住在哪里」表现在这么说，并把原先笼统写作「审计链·在内存」的那一格点明为 **AI** 审计链（它本来就是）。

- **生成物的决策轨迹也答得出它的治理那一半了。** 生成物发出的 `GET /ai/conversations` 列出调用方自己的会话，`GET /ai/conversations/{id}/trace` 答 `{conversation, steps}` —— 回合，加上那些回合产出的治理事件，按**事情发生的先后**并成一条时间线。会话 id 记在**引擎写下的每一行旁边**：审计行上、把提议从「提议时刻」带到「裁决时刻」的待决记录上、以及批准产出的副作用上 —— 故一个**在自己的请求里**到达的批准，仍点得出提议它的那条会话。**没有会话**时写下的行（一次任务运行）带 null、也不出现在任何追踪里 —— 而「没有会话」本来就该如此。副作用清单的 `conversationId` 不再是写死的 null，生成物 README 也不再那么说。

- **生成物也服务治理策略了，而且施加它。** 生成物发出的 `GET` / `PUT /ai/governance/policy` 按冻结的 `governance-policy` 作答 —— 仅管理员 —— 给出逐工具的覆盖与审计粒度，外加对象自带的 `updatedAt` 与 `revision`，而一次 GET 的结果也是一份合法的 PUT 体。它说的东西在**引擎每次调用都要过的那道闸**上被施加：被禁用的工具与角色白名单拒绝、显式的门控档位移动那道闸、粒度管的是 AI 审计链（`off` 一行不写）。存储**拒绝**它施加不了的提交 —— 未注册的工具、冻结三值之外的 `mode`、本应用词表之外的角色、未识别的键 —— 回 `400` 并给出理由，且是**整份**拒绝、不是逐字段半应用。名录的 `enabled` 与 `allowedRoles` 读自**同一份**策略，故清单与那道闸不可能各执一词；`revision` 是冻结对象写明的**前十二位十六进制内容指纹**，取自**有序内容**。

### 修复

- **生成物里的高影响动作需要第二个人。** 生成物引擎原先把 `REQUIRE_APPROVAL` 当成普通确认，于是 R4 工具或策略
  `mode: "approval"` 可由其发起者自己批准 —— 一项声明的控制被降成一次点击。待决行现在带上模式，认领时拒绝自批准，
  而否决仍对持有 token 的任何人开放，与运行时一致。

- **副作用账本上的 own 范围读不再扩大到所有人的行。** 列表原先把任何 `read` 授予都当作全局；带 owner 字段的
  档 B 规则现在仍收窄到调用方自己的行。全局分支要求能力的范围是 `all`。

- **过长的 `User-Agent` 不再能抑制一条操作审计行。** 客户端可控的值在插入前先截到列宽，故行会被记下，而不是在
  值溢出时丢掉。

- **未变更的策略不再追加重复的历史快照。** 存储先比对内容 revision 与最新快照，再决定是否写入。

- **操作审计行的 `userId` 与冻结对象一致。** 那里 `userId` 是 `number|null`，故数字形式的身份发成 number、
  其它身份发 null，并把字符串保留在 `username` 里而不是丢掉。生成物发出同一形状。

## [0.1.7] - 2026-10-12

**English**

### Added

- **A turn can name a place in a console, and both endpoints say so.** Navigation is an ordinary tool —
  a read, so it takes no write claim, records no side effect and asks for no confirmation — but what it
  returns is a route, and until now no route could leave this runtime. `chat-response` declares
  `navigateTo` and `sse-event` declares a `navigate` event; the reference fills and emits both, and this
  runtime did neither. `/ai/chat` now answers with the field when a turn named one, and the stream sends
  the event after the reply, where a console has already begun rendering the turn. The extraction lives
  in one place, `ChatTurnService.Turn.navigateTo()`, because both endpoints report the same turn and a
  second reading would be a second place for them to disagree. **No contract change was needed** —
  both are already declared by the frozen objects.

- **The model-backed replier is told what the deployment is.** Its system prompt now carries the preset,
  the feature flags, the business modules and the tool catalogue, because "what modules does this system
  have" is a question only the system can answer, and a model left to guess will guess. It carries no
  governance metadata: the risk level, the confirmation flag and the revoke class stay server-side,
  which is the planner's own rule applied to the second seam. Blank in, blank out — a deployment that
  declared nothing gets no block rather than an empty heading.

> **⚠ Known, and deliberately not fixed in this version.** The chat stream does not conform to the
> frozen `sse-event` schema in two places, both of which predate this change: `tool_start` omits the
> `summary` its payload requires, and `confirmation_request` sends `mode: "confirmation"` where the
> frozen enum is `immediate` / `approval` / `run`. Nothing in this repository asserted the stream
> against the schema at all. `ConsoleNavigationTest` now holds the `navigate` event to it and records
> the gap in place rather than narrowing the check quietly.

### Changed

- **A successful execution now leaves two lines on the audit trail, not one.** An intent row is written
  before the tool runs, answering the contract's `attempted` — the value whose own description says it is
  not an outcome, and that its outcome is the line after it. The result line was already best-effort: it
  is written after the tool's write, so a host write that landed and a bookkeeping step that did not
  would otherwise leave the attempt unrecorded, which is the half an operator goes looking for.
  **Anything that counts rows now sees one more per execution** — a host's own gates included — and the
  generated application writes the same row in the same place.

### Fixed

- **A thrown execution frees its write claim only when the throw proves nothing was written.** The
  pre-execution claim is released on failure so that a call which failed can be made again — but the
  release was made on *any* `RuntimeException` out of the engine's try, and a throw from the tool is not
  proof: the tool is where the write happens, so it can write and then fail. The ledger probe cannot
  catch that either, because the effect is only recorded after `execute` returns. The key is now freed
  only for a refusal raised *before* the tool does anything — the authorization rule's or a host row
  scope's — and everything else holds it until the lease runs out. The audit is unchanged and stays as
  broad as it was: every throw is still a line, whatever its type.

- **A write that names no result fails on the record, instead of throwing a constraint violation past
  its own audit line.** The ledger requires every effect to name what it produced — the contract's
  effect object makes `resultId` required, and the column will not take null — so a tool that declared
  a result type and then returned no numeric `id` reached an insert that threw. It threw *after* the
  tool had already run, and before the block's own audit line, leaving a landed write, no effect, no
  outcome on the trail, and a 500. Such a call now ends as a recorded failure with a reason: the
  effect stays unrecorded (nothing can record a subject it was never shown) and the claim stays taken,
  because the tool ran and the write may have landed. Naming a result id in the tool's result is what
  keeps it from happening, and `AiTool.resultType()` now says so where a tool's author will read it.

**中文**

### 新增

- **一个回合可以点名控制台里的一处，而两条端点都说出来。** 导航就是一个**普通工具** —— 读，故不占写声明、不记副作用、不要确认 —— 但它交回的**是一条路径**，而在此之前**没有任何路径能离开这个运行时**。`chat-response` 声明了 `navigateTo`、`sse-event` 声明了 `navigate` 事件；参照实现两者都填、都发，而本运行时一个都没做。现在 `/ai/chat` 在本回合点过名时答出那个字段，流则把事件发在 reply **之后** —— 那是控制台**已经**开始渲染本回合的位置。提取只有一处（`ChatTurnService.Turn.navigateTo()`），因为两条端点报的是**同一个回合**，第二次读就是第二个「两处会不一致」的地方。**契约无需改动** —— 两者都是冻结对象自己声明过的。

- **模型版的 replier 现在知道这个部署是什么。** 它的 system prompt 里带上了 preset、特性开关、业务模块与工具目录，因为「这个系统有哪些模块」是**只有系统自己**答得上来的问题，而放任不管的模型**会去猜**。它不携带任何治理元数据：风险级、是否需确认、撤销档都留在服务端 —— 那是 planner 自己的规矩用在**第二条缝**上。空进空出：什么都没声明的部署得到的是**没有那一块**，不是一个空的抬头。

> **⚠ 已知、且本版刻意不修。** 这条聊天流在冻结的 `sse-event` schema 上有两处不合规，且都早于本次改动：`tool_start` 漏了它载荷所要求的 `summary`，而 `confirmation_request` 发的 `mode: "confirmation"` 不在冻结枚举（`immediate` / `approval` / `run`）里。本仓**别处没有任何断言**把这条流拿去对 schema。`ConsoleNavigationTest` 现在把 `navigate` 事件钉在它上面，并把缺口**记在原地**，而不是把检查悄悄收窄。

### 变更

- **一次成功的执行从此在审计轨上留下两行，而不是一行。** 在工具跑之前先写一条 intent 行，作答契约的 `attempted` —— 那个值自己的说明就写着**它不是一次结局**、结局在它**后面那一行**。结果行本来就是 best-effort：它写在工具的写**之后**，于是一次落了地的宿主写、加一步没落地的记账，本来会让这次尝试**无人记录** —— 而那正是运维第一个会去找的那一半。**凡是要数行的地方，从此每次执行都多一行** —— **宿主自己的门禁也在内**；生成物在**同一位置**写同一行。

### 修复

- **一次抛出的执行，只在抛出**证明**什么都没写时，才放走它的写声明。** 执行前的声明在失败时被释放，好让一次失败的调用可以重做——但那次释放发生在引擎 try 内**任何** `RuntimeException` 上，而**工具的抛出不是证据**：工具正是写发生的地方，所以它可以写了才失败。账本探测也接不住这件事，因为 effect 只在 `execute` 返回**之后**才记。现在**只有工具做任何事之前**抛出的拒绝才放走键——授权规则的、或宿主行范围的——其余一切把键留到租约跑完。**审计未动、照旧一样宽**：每一次抛出都仍然是一行，无论它是哪一类。

- **一次没说清结果是什么的写，**在轨上**失败，而不是把一条约束违例抛过它自己的审计行。** 账本要求每条 effect 说清它产出了什么——契约的 effect 对象把 `resultId` 列为必填，而那一列不收 null——于是一个声明了结果类型、却交回不出数字 `id` 的工具，会走到一条抛异常的插入。而它是在**工具已经跑完之后**、又在**这个块自己的审计行之前**抛的，留下的是：写落了、没有 effect、轨上没有结局、以及一个 500。这样的调用现在以**记录式失败**收尾、带理由：effect 仍然不落（没有东西能记下一个它从未被展示过的主体），领取仍然占着——因为工具跑过了、写可能已落地。**在工具的结果里点名一个结果 id** 才是让它不发生的办法，而 `AiTool.resultType()` 现在把这句话写在工具作者会读到的地方。

## [0.1.6] - 2026-10-11

**English**

### Added

- **An audit row says what happened, and which tool it was about.** `GET /api/v1/audit/logs` answers the
  frozen `ai-audit-log-row` **v5**: two out-of-chain columns, `outcome` — a frozen vocabulary of ten
  values — and `tool`. Until now the outcome could only be read by matching free text in `detail` and the
  tool name by taking that string's first token — a parse that holds on one implementation and not the
  other, because their `detail` grammars differ. The runtime now writes both at every one of its eleven
  audit sites. `ai_audit_logs` gains the two columns (**V7**, h2 · mysql · postgresql), and neither
  enters the hashed payload, so every row already written still verifies.

- **A write whose type a deployment owns can now be revoked.** `local_compensate` used to name one
  entity — the sample module's follow-up — so any other type ended in a conflict that, thrown before the
  audit line, left no trace that a revocation had been attempted at all. `SideEffectCompensator` is the
  seam that replaces it: one bean per `resultType`, looked up rather than known, and two compensators on
  one type fail the application at startup instead of being resolved silently at revoke time. A
  registered compensator also takes precedence over the built-in path, so a deployment can replace that
  rather than live beside it.

### Changed

- **This implementation answers contract `v1.7.0`.** The vendored snapshot and both workflows' pins moved
  from `v1.1.0` — a version this runtime had long stopped answering for, which is why the drift gate
  stayed green while the snapshot fell two schema generations behind. The gate now compares against the
  contract this repository actually speaks.

- **The vendored snapshot gained the schemas it had been missing.** Refreshing it brought `schemas/v4`
  and `schemas/v5` in full, and those are not only the audit row: `trace-step` is now v2, and
  `side-effect-revoke` v4, `capabilities` v2. Every file is byte-identical to contract `v1.7.0` and none
  of it changes what this runtime serves — it was the snapshot that had fallen behind, not the code. It
  is named here because a consumer comparing versions would otherwise see the objects move without
  anything saying so.

**中文**

### 新增

- **审计行说得清发生了什么、以及它是关于哪一个工具的。** `GET /api/v1/audit/logs` 现在答冻结的
  `ai-audit-log-row` **v5**：两列链外归因列 —— `outcome`（**十个值**的冻结词表）与 `tool`。在此之前，结局只能
  靠在 `detail` 里匹配自由文本读出、工具名只能取那串的第一个 token —— 而那个解析**在一个实现上成立、在另一个
  上不成立**，因为两者的 `detail` 文法不同。现在运行时**十一处**审计写入点都会写下这两列。`ai_audit_logs` 加
  这两列（**V7**，h2 · mysql · postgresql），且两者都**不进哈希载荷** ⇒ **已经写下的每一行仍然验得过**。

- **部署自己那种类型的写，现在撤得动。** `local_compensate` 此前只点名一个实体 —— 样例模块的 follow-up ——
  于是别的类型都以一个冲突收场；而那个冲突抛在审计行**之前**，连「试过撤销」都不留痕。取而代之的是
  `SideEffectCompensator` 这条缝：**一个 `resultType` 一个 bean**，运行时**查表**而不是**知道**；同一个类型上
  两个补偿器会让应用**启动即失败**，而不是在撤销那一刻被悄悄定夺。注册的补偿器**优先于**内置那条路，故部署可以
  **替换**它、而不是与它并存。

### 变更

- **本实现回答契约 `v1.7.0`。** vendored 快照与两个 workflow 的 pin 从 `v1.1.0` 抬起 —— 那是一个本运行时早已
  不再回答的版本，也正是**快照落后两代 schema 而漂移闸一直绿着**的原因。闸现在比的是**本仓真正在说的那份契约**。

- **vendored 快照补回了它一直缺的那些 schema。** 刷新一次带进来完整的 `schemas/v4` 与 `schemas/v5`，而那不只是
  审计行：`trace-step` 现在 v2、`side-effect-revoke` v4、`capabilities` v2。每一个文件都与契约 `v1.7.0`
  **逐字节一致**，且都不改变本运行时**伺服的东西** —— 落后的是**那份快照**，不是代码。在这里点名，是因为否则
  一个对着版本号比对的消费者会看到对象动了、却没有任何一句话说明过。

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

- **Three objects this runtime does not produce are listed as *not served*.** A request body, an error
  code vocabulary and a tool list a client fetches from elsewhere — each was named by the corpus, and
  none of them is something a runtime *publishes*, so the mapping document now says so rather than
  leaving the absence to be inferred.
- **Where a deployment's first token comes from is written down.** Core has no sign-in endpoint by design
  — it verifies a delegation token and never issues one — and what was missing was never code but a
  written, executable answer to where that token comes from: what the entry verifier actually requires,
  and the two ways a deployment produces one. `docs/deployment-first-token.md`.
- **Four shapes two hosts arrived at independently are now in this module.** `ActingCaller` — the caller
  published for the length of the code an AI tool runs, because the engine's tool path is outside the
  request's security filter chain, where the framework's own "current user" throws or answers nothing.
  `ActingCallerId` — ask the request's own identity, fall back to the published caller, parse or null.
  `ModuleRoute` — a module contributes its routes as beans. `ModuleRoutePlanner` — the deployment's
  routes first, the runtime's rule-based router last, delegated rather than copied. **Only the shapes are
  lifted**: which utility reads a request's identity stays with each deployment, both hosts are unchanged
  by this, and this is not a host contract — two integrations are not enough to draw one.

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

- **本运行时不产出的三个对象，现在明列为 `not served`。** 一个**请求体**、一份**错误码词表**、一张**客户端自己去别处取的**
  工具清单 —— 三个都曾被语料点名，而**没有一个是运行时「发布」的东西**，所以映射文档现在**说出来**，而不是把「没有」留给
  别人去推。
- **一个部署的第一枚令牌从哪来，写下来了。** core **故意没有登录端点** —— 它只验证委托令牌、从不签发 ——
  而缺的**从来不是代码**，是一个**能照着做**的答案：入口验签器究竟要求什么、部署方**产它的两条路**是什么。
  见 `docs/deployment-first-token.md`。
- **两个宿主各自独立走到的四个形状，现在住在本模块里。** `ActingCaller` —— 正在行动的那个调用者，在 **AI 工具
  跑的那一段**里发布出来，因为引擎的工具路径在请求的安全过滤器链**之外**，框架自己那个「当前用户」在那儿要么抛、
  要么什么也答不出。`ActingCallerId` —— 先问请求自己的身份、落回已发布的那位、解析成 id 否则 null。
  `ModuleRoute` —— 模块以 bean 形式贡献自己的路由。`ModuleRoutePlanner` —— 部署方自己的路由在前、运行时的
  规则路由器在后，**委托**而不是抄。**上提的只有形状**：**读请求身份的是哪一个工具类**留在各部署手里，两个宿主
  都不因本次改动而改变，而且这**不是**一份宿主契约 —— **两次集成不够画一份**。

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

[Unreleased]: https://github.com/rain6fish/KeelBase4J/compare/v0.1.15...HEAD
[0.1.3]: https://github.com/rain6fish/KeelBase4J/releases/tag/v0.1.3
[0.1.2]: https://github.com/rain6fish/KeelBase4J/releases/tag/v0.1.2
[0.1.1]: https://github.com/rain6fish/KeelBase4J/releases/tag/v0.1.1
[0.1.0]: https://github.com/rain6fish/KeelBase4J/releases/tag/v0.1.0
