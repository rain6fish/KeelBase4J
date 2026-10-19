# Wire object → endpoint (this runtime)

The replay corpus (`conformance/vectors/scenarios/`, see `conformance/vectors/README.md`) asserts about
**wire objects**, not paths — a `call` names an object or a tool, and each runtime maps that to its own
surface. This is **this runtime's answer to that mapping**, written down so a third party can run the
corpus against it without reverse-engineering the mapping out of `ScenarioReplayTest`, and so that **an
object this runtime does not expose** can be told apart from **a mapping that is wrong**. (Two carriers
replaying the same corpus is what surfaced the need for this: see `docs/ARCHITECTURE.md` §3.2.1.)

## Served

| wire object | this runtime's surface | notes |
|---|---|---|
| `audit-chain-verification` | `GET /api/v1/audit/verify` | the frozen object, `chain` included — **administrators or a read-only audit reader** (`read` on `AuditLog`), as the object's own implementation gates it |
| `ai-audit-log-row` | `GET /api/v1/audit/logs` | one row per audit record, filtered by `userId` / `since` / `isError` — **administrators or a read-only audit reader** (`read` on `AuditLog`); the reference's `agentId` / `orgId` / `denied` filters are **refused** rather than ignored (see below) |
| `chat-response` | `POST /api/v1/ai/chat` | the frozen object with nothing added — see below for where the outcome went |
| `side-effect-revoke` — an effect | `GET /api/v1/ai/tool-effects` | list envelope `{total, page, limit, items}`. **Administrators or a read-only audit reader** (`read` on `ToolEffect`) see every caller's effects; anyone else sees their own only. The projection is the same for both readers — the field whitelist for a read-only reader is still to be decided, and this change adds no field |
| `side-effect-revoke` — revoke result | `DELETE /api/v1/ai/tool-effects/{id}` | `{effectId, resultType, revokeClass, revokeStatus, revoked}` |
| `permission-capability-list` | `GET /api/v1/auth/me/permissions` | what this identity may do, and on what basis |
| `capabilities` | `GET /api/v1/app/capabilities` | |
| `app-provenance` | `GET /api/v1/app/provenance` | |
| `ai-tool-inventory` | `GET /api/v1/ai/tools` | one item per registered tool — **administrators or a read-only audit reader** (`read` on `ToolInventory`). The governance half is **asked of the engine's own gate** rather than restated, so a tool cannot be described one way and gated another. Three fields answer their defaults because nothing here declares them — see below |
| `conversation-data` | `GET /api/v1/ai/conversations` · `GET /api/v1/ai/conversations/{id}/trace` (its `conversation`) | the caller's **own** conversations — user-scoped rather than administrator-only. A list item carries its **two most recent** turns and derives `lastActivityAt` from the newest; a trace's carries the whole conversation |
| `trace-step` | `GET /api/v1/ai/conversations/{id}/trace` (`steps`) | the conversation's turns as `input` / `assistant` steps, merged in time order with the **governance half** — the `tool_call`, `confirmation` and `effect` steps its own audit and side-effect rows carry. A row written with no conversation (a task run, an MCP caller) is genuinely not this conversation's and does not appear — see below |
| `operation-audit-log-row` | `GET /api/v1/audit/operations/logs` | the **operations** chain — business writes, **administrators or a read-only audit reader** (`read` on `OperationAudit`), newest first, envelopes as `{total, page, limit, items}`. A **second, separate** chain from the AI audit beside it: nothing joins the two, and neither is derived from the other. What the row deliberately does not carry is named below rather than left blank |
| `my-confirmation-item` (the queue across callers) | `GET /api/v1/ai/confirmations` | every confirmation record, read-only, newest first, envelopes as `{total, page, limit, items}` — **administrators or a read-only audit reader** (`read` on `Confirmation`). One projection with `/ai/my/confirmations`: nothing is added for the reader. Reading moves nothing; deciding, approving and executing keep their own entries and guards |
| `governance-policy` | `GET` / `PUT /api/v1/ai/governance/policy` | the policy in force and the surface a console changes it on — **readable by administrators or a read-only audit reader** (`read` on `GovernancePolicy`); **writable by administrators only** (the write action is the seam's `update`, which `manage` expands into). A written policy is **enforced** on the gate the engine runs every call through, not merely stored — see below. A submission naming a tool, a mode or a role this deployment cannot apply is refused with a `400` rather than stored and ignored |

**Two of `ai-tool-inventory`'s fields now come from the governance policy; the third still has no
source.** `enabled` and `allowedRoles` are read from the same per-tool override store the gate reads its
switches from, so the inventory and the gate cannot disagree about whether a tool is on or who may call
it; with no policy written they answer their defaults (`true` and an empty list), which is what "nothing
was declared" looks like here. `permissions` still answers `null`, and `null` is the honest form: the
reference fills it from a permission set this runtime does not have, and inventing one would put a
permission on a console nobody set.

**A written policy changes what happens next, not only what a page shows.** The store refuses rather
than stores a setting it cannot apply, and the three it can apply are applied where it matters: a
disabled tool and a role allowlist refuse at the gate, a gate mode moves it in either direction (R5, the
irreversible or external tools, is decided before the policy is read, so a policy cannot relax it), and
the audit granularity governs the AI audit chain — `off` writes no rows, while `write` and `all` come to
the same chain here because this runtime records only tool-scoped rows. A decision's authorization
reasons name which check refused it (`risk_policy` / `tool_enabled` / `role_allowed`) and carry the
`revision` of the policy it was taken under.

**`ai-tool-inventory` 里有两个字段现在取自治理策略，第三个仍无来源。** `enabled` 与 `allowedRoles` 读自
**那道闸自己读开关的同一个逐工具覆盖存储**，故这张清单与那道闸**不可能**对「工具开没开、谁能调」各执一词；未写过
策略时它们答默认值（`true` 与空表），那就是「什么都没声明」在本运行时的样子。`permissions` 仍答 `null`，而 `null`
是**诚实**的形态：参照实现用一个本运行时没有的权限集去填它，**编造一个**等于把一项**没人设过**的权限显示到控制台上。

**一份写下的策略改变的是「接下来会发生什么」，不只是页面显示什么。** 存储**拒绝**它施加不了的设置、而不是存下来，
而它能施加的三项都施加在**要紧的地方**：被禁用的工具与角色白名单在闸上拒绝，门控档位把闸**双向**移动（R5 —— 不可逆
或对外的工具 —— 在**读策略之前**就定了，故策略**不能**把它放宽），审计粒度管的是 **AI 审计链** —— `off` 一行不写，
而 `write` 与 `all` 在这里落到同一条链，因为本运行时只记**工具范围**的行。一次决定的授权依据说得清是**哪条检查**拒的
（`risk_policy` / `tool_enabled` / `role_allowed`），并带上做出该决定时所依据策略的 `revision`。

**A trace here holds both halves of the conversation's story.** The reference's decision trace carries
the turns and, with them, the tool calls, the confirmation decisions and their side effects. So does
this one now: the id of the conversation a call was made in is recorded where the call is made — on the
audit row, on the confirmation row that carries a proposal from proposal time to decision time, and on
the side-effect row the write produced — so the trace places all three inside the conversation. The
turns and the governance events are merged into one timeline in the order they happened, because a tool
call belongs between the message that proposed it and the reply that reports it.

**What a trace still does not carry.** A row written with no conversation — a task run, an MCP caller —
is genuinely not this conversation's, so it does not appear; and neither does a call's argument string,
because this runtime does not store one on the audit row, and inventing it would describe a call the row
never held. Those absences are the row's own, not a statement about the conversation, and a test holds
the linked steps in place so they cannot go quiet.

**这里的追踪装的是会话故事的两半。** 参照实现的决策轨迹带回合，也带工具调用、确认裁决与它们的副作用。
本运行时现在也一样：一次调用所在的**那条会话的 id**，就在**调用发生的地方**被记下 —— 记在审计行上、记在把提议从
「提议时刻」带到「裁决时刻」的那条确认行上、也记在那次写产出的副作用行上 —— 于是追踪把这三者都放进会话里。
回合与治理事件按**事情发生的先后**并成一条时间线，因为一次工具调用落在**提议它的那句**与**报告它的那句回复**之间。

**追踪仍然不装的东西。** **没有会话**时写下的行 —— 一次任务运行、一个 MCP 调用方 —— 本来就不属于这条会话，故不
出现；也没有调用的参数串，因为本运行时**不在审计行上存它**，编一个等于**描述一次那一行从未持有过的调用**。这些缺席
是**行自身**的、不是关于这条会话的陈述；并有测试把**已链接的那些步**钉住，不许它们悄声消失。

**The operations row carries what this runtime sources, and names what it does not.** The reference
stores a request body (redacted field by field against a PII declaration), a field-level before/after
diff, the feature names a mapping declares, and two hash columns. This runtime has no PII declaration to
redact by, nothing that produces a diff, no feature map, and — for the hashes — no second chain head, so
each of those is **absent rather than invented**; `V9__operation_audit_log.sql` states the same list where
the table is created. What the rows do carry is all sourced: the action in the frozen vocabulary
(`LOGIN` / `LOGOUT` / `UPLOAD` / `CREATE` / `UPDATE` / `DELETE`), the method and path, the caller read
*before* the request's security context is gone, the route's `id` where it declares one, the status, the
direct peer, and the user agent.

**The recorder is on by default**, and `keelbase.operations-audit.enabled=false` turns it off. It is a
request interceptor, so it captures the writes of whatever application embeds this runtime — which is
what makes a console page work in a host at all — and the switch is there for a host that already keeps
an operations log of its own. The AI surface is deliberately outside it: its calls have the chain next
door, and a turn, a confirmation and a revoke are read as a decision trace, not as business operations.

**操作审计那一行装的是本运行时取得到的，并点明它没有的。** 参照实现会存请求正文（按 PII 声明**逐字段**打码）、
字段级前后差异、映射声明的功能名，以及两个哈希列。本运行时**没有 PII 声明**可依、**没有产出差异的东西**、**没有功能
映射**，而哈希那两列**没有第二个链头** —— 所以每一样都是**缺着、而不是编的**；`V9__operation_audit_log.sql` 在建表
处写着同一份清单。它**确实**装的是：冻结词表里的 `action`（`LOGIN` / `LOGOUT` / `UPLOAD` / `CREATE` / `UPDATE` /
`DELETE`）、方法与路径、**在请求的安全上下文消失之前**读到的调用者、路由声明了的那个 `id`、状态、直接对端、以及
user agent。

**记录器默认开着**，`keelbase.operations-audit.enabled=false` 关掉它。它是一个**请求拦截器**，所以它捕获的是
**嵌入本运行时的那个应用**的写 —— 这正是控制台的页在宿主里跑得起来的原因 —— 而那个开关是给**自己已经有一份操作日志**
的宿主留的。**AI 面刻意在它之外**：那些调用有隔壁那条链，而一个回合、一次确认、一次撤销读的是**决策轨迹**、不是业务操作。

**One answer is this runtime's own object rather than the frozen one** — it is a mapping, not a
divergence, and it is declared in `docs/ARCHITECTURE.md` §3.2.1: the approve response is an
`ExecutionOutcome` and not `confirmation-decision`.

**The chat answer used to be a second row in that sentence, and where its outcome went is this
runtime's answer to a corpus question.** The answer carried `status`, `data`, `token`, `effectId` and
`error` alongside the conversation turn, so a corpus expectation about a tool call could be read off
`POST /api/v1/ai/chat` (`executed` ⇔ `status=executed`; `requiresConfirmation` ⇔
`status=pending_confirmation`). The contract files `chat-response.schema.json` under the title naming
that path with `additionalProperties: false`, the reference puts none of those fields there, and the
protocol's prose says a non-streaming call returns no confirmation token — so the answer is now that
object with nothing added, and the facts live on surfaces of their own: a tool call's result is
reported as **events** on `POST /api/v1/ai/chat/stream`, and a pending write is found in
`GET /api/v1/ai/my/confirmations`, whose items carry the token. A reader of this table should take the
stream, not the reply text, as the structured place those facts are found — the replier is a
replaceable bean.

**聊天那条答案过去是上面那句话里的**第二条**，而它的结果去了哪里，就是本运行时对语料那一问的回答。** 那条答案
在对话回合之外还带着 `status`、`data`、`token`、`effectId`、`error`，于是关于一次工具调用的语料期望可以直接从
`POST /api/v1/ai/chat` 读出来（`executed` ⇔ `status=executed`；`requiresConfirmation` ⇔
`status=pending_confirmation`）。而契约把 `chat-response.schema.json` 登记在**指名那条路径**的标题之下、
写着 `additionalProperties: false`，参照实现在那儿**一个都不放**，协议散文也说**非流式调用不返回确认 token**
—— 所以这条答案现在**就是**那个对象、一点没多加，而那些事实住在**它们自己的面**上：一次工具调用的结果是
`POST /api/v1/ai/chat/stream` 上的**事件**，而待确认的写要在 `GET /api/v1/ai/my/confirmations` 里找 ——
它的项**带着 token**。读这张表的人应当把**流式**（不是 reply 的文字）当作那些事实的结构化去处：replier 是
**可替换的 bean**。

**What is *not* in doubt.** The corpus judges a tool call by the facts `executed` /
`requiresConfirmation` and never names `status`, `token` or `effectId`, which is why it survived the
move: the runner reaches those facts on the stream, where the reference reports the same turn.

**不存疑的一点是**：语料**按 `executed` / `requiresConfirmation` 这两个**事实**判一次工具调用**，
**从不点名** `status`、`token` 或 `effectId` —— 这也正是它**扛住了这次搬家**的原因：runner 在**流式**那条上
够到那两个事实，而参照实现报**同一个回合**走的也是那条路。

**The audit rows are readable now, and one of the filters is a refusal by design.** The chain could be
appended to and verified but not read, so "which rows did this caller leave in this period" had no
answer. `GET /audit/logs` answers it in the frozen `ai-audit-log-row` shape — the six fields this
runtime has, and only those. The fields the object declares that this runtime does not record
(conversations, agent and delegation identity, token counts, feedback, an authorisation verdict) are
**absent** rather than sent as nulls: absence says "this deployment has no such concept", while a null
claims the row could have carried a value. The reference's query also filters on agent, organisation
and an authorisation-verdict view; this runtime records none of those, and a filter that is accepted
and not applied returns a list that **looks filtered and is not** — so those are refused, not ignored.

**审计行现在读得回来了，而其中一个筛选按设计是「拒绝」。** 链能追加、能校验，却**读不回来**，于是
「这个调用方在这一段时间里留下了哪几行」**没有答案**。`GET /audit/logs` 按冻结的 `ai-audit-log-row` 形状答它 ——
本运行时**有的那六个字段**，也**只有**那六个。对象声明了、而本运行时不记的那些字段（会话、agent 与委托身份、
token 计数、反馈、授权结论）是**缺席**的、不是发成 null：**缺席**说的是「本部署没有这个概念」，而 null 会**主张**
这一行本可以带一个值。参照实现那条查询还按 agent、组织与一个授权结论视图筛选，本运行时**一个都不记** —— 而一个
**被接受却没被施加**的筛选回出来的是一份**看着像筛过、其实没有**的列表，所以那三个是**被拒绝**、不是被忽略。

## Not served

An object below is one the corpus names and this runtime **does not expose** — a **"not applicable"**,
not a mapping to fix. (The same list, from the runner's side, is the classification its inventory test
asserts.)

| wire object | why not |
|---|---|
| `permission-decision` | the frozen decision is computed (`PermissionAuthorizer.decide`) but only the capability list is on the wire — there is no decision-over-action×subject endpoint |
| `evidence-package` | no evidence-root export exists here — under a Java host deployment, evidence packages are obtained from the TypeScript governance console, not from this runtime (`JV-64`, decision 乙, 2026-10-17). See [README "What this is not"](../README.md#what-this-is-not) item 8 and `docs/deployment-first-token.md` §7 |
| `side-effect-revoke` read as the **governance view** | no governance-view endpoint; and this runtime's only local target type is `follow_up`, so a `resultType` of `crm_task` could not hold even if there were one |
| the reference application's tools — `delete_customer` · `create_event` · `query_events` | a tool **inventory** belongs to the **application**, not the contract: this runtime registers `analyze_customer_risk` and `create_followup`. The packs declare the tools they assume (the pack's `tools`), so "this runtime lacks that tool" is readable off the corpus. **⚠ 2026-10-13, corrected in place (original kept):** the reason as written — "a tool inventory belongs to the application, not the contract" — **no longer holds literally**, because the contract **freezes** an `ai-tool-inventory` **object** and this runtime now **serves** it (see the table above). **What still holds is the other half**: *which tools exist* is the application's, and the contract freezes the **shape**, not the **list** |
| `confirm-decision-body` | a **request** body — the corpus names it for callers. There is nothing here to *produce*, so "does this runtime serve it" is not a question with a yes or a no |
| `api-error-code` | a **vocabulary**, not a payload. No endpoint emits it; the check it would support is a different one (are the codes this runtime *does* emit inside the list), which is not conformance to an object |
| `mcp-tool-list` | nothing is emitted here: `keelbase4j-mcp`'s `McpServerTools.listTools()` is this runtime **consuming** a tool list from an external server, not publishing one |

**The `evidence-package` row is a declaration, not a gap to close.** This runtime keeps an audit chain and serves `GET /audit/verify`, but it has **no** evidence-package / evidence-root export, and none is planned on this line — under a Java host deployment, evidence packages are obtained from the TypeScript governance console (`JV-64`, option 乙). Any coverage statement for such a package must also carry `D28 ④`: a host that owns the business write does not write that write and its audit row in one transaction.

**`evidence-package` 这一行是一份声明，不是一个待补的缺口。** 本运行时持有一条审计链、也服务 `GET /audit/verify`，但**没有**证据包 / 证据根导出，本线当前也没有计划加——Java 宿主部署下的证据包从 TypeScript 治理控制台取得（`JV-64`，乙案）。这类证据包的任何覆盖声明还必须带上 `D28 ④`：自己持有业务写的宿主，不会把那次写与它的审计行写在同一个事务里。

**These three were moved out of "the objects this runtime produces" by decision, not discovered to be missing.** The map in `KeelBase-Private/KeelBase4J/JV-46-覆盖图_2026-10-09.md` had listed them as remaining positions to read (§9.4); reading each one showed that the registry sources the objects this runtime *does* answer at the reference implementation — so a row saying "not produced here" is the honest form, and the alternative (making this runtime emit them) is a product change this runtime has not chosen.

**那条「工具清单属于应用、不属于契约」的读法，2026-10-13 就地更正。** 它**字面上已不成立**：契约**冻着一个**
`ai-tool-inventory` **对象**，而本运行时现在也**服务它**（见上面那张表）。**仍然成立的是另一半**：**哪些工具存在**
是应用的，契约冻的是**形状**、不是**清单** —— 所以那一行说的「参照实现的这几个工具名在这里没有」仍然对。

## This runtime's own surface (not a wire object)

`GET /api/v1/keelbase/status` is **not** a frozen wire object and the corpus does not assert it. It is
this runtime's integration-health report: registered tools by read/write and risk, the effective R3/R4
gate read from the engine's own `GovernanceService.decide` call, audit row counts and timestamps plus
chain verification, and a local delegation sign-then-verify probe. Each dimension carries its own status
and the reading behind it; a dimension that cannot be measured reports `unknown` or `not_applicable`
rather than green. It is authenticated and guarded by `read` on `IntegrationStatus`.

**本运行时自己的面（不是 wire 对象）。** `GET /api/v1/keelbase/status` **不是**冻结 wire 对象，语料也不断言它。
它是本运行时的接入健康度报告：按读/写与风险统计的已注册工具、从引擎自己的 `GovernanceService.decide` 读出的
生效 R3/R4 门控、审计行条数与时刻加链校验、以及本地委托签发/验签探针。每个维度带自己的状态与状态背后的读数；
测不到的维度报 `unknown` 或 `not_applicable`，而不是绿色。它已认证，由 `IntegrationStatus` 上的 `read` 把关。

## Scope

The table covers the objects the corpus asserts about. It is deliberately not the whole registry — a
row here means "the corpus may name this and we answer it", and a missing row means we do not. A tool
call is reached by a **message the planner routes** rather than by a tool name (`ScenarioReplayTest`
carries the routing table), which is the one place this runtime's mapping is not a plain path.
