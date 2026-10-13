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
| `audit-chain-verification` | `GET /api/v1/audit/verify` | the frozen object, `chain` included — **administrators only**, as the object's own implementation gates it |
| `ai-audit-log-row` | `GET /api/v1/audit/logs` | one row per audit record, filtered by `userId` / `since` / `isError` — **administrators only**; the reference's `agentId` / `orgId` / `denied` filters are **refused** rather than ignored (see below) |
| `chat-response` | `POST /api/v1/ai/chat` | the frozen object with nothing added — see below for where the outcome went |
| `side-effect-revoke` — an effect | `GET /api/v1/ai/tool-effects` | list envelope `{total, page, limit, items}` |
| `side-effect-revoke` — revoke result | `DELETE /api/v1/ai/tool-effects/{id}` | `{effectId, resultType, revokeClass, revokeStatus, revoked}` |
| `permission-capability-list` | `GET /api/v1/auth/me/permissions` | what this identity may do, and on what basis |
| `capabilities` | `GET /api/v1/app/capabilities` | |
| `app-provenance` | `GET /api/v1/app/provenance` | |
| `ai-tool-inventory` | `GET /api/v1/ai/tools` | one item per registered tool — **administrators only**. The governance half is **asked of the engine's own gate** rather than restated, so a tool cannot be described one way and gated another. Three fields answer their defaults because nothing here declares them — see below |
| `conversation-data` | `GET /api/v1/ai/conversations` · `GET /api/v1/ai/conversations/{id}/trace` (its `conversation`) | the caller's **own** conversations — user-scoped rather than administrator-only. A list item carries its opening two turns; a trace's carries the whole conversation |
| `trace-step` | `GET /api/v1/ai/conversations/{id}/trace` (`steps`) | the conversation's turns as `input` / `assistant` steps, plus **one `notice`** — see below for why that notice is the honest form rather than an omission |

**Three of `ai-tool-inventory`'s fields have no source in this runtime, and they answer their defaults
rather than a guess.** The reference fills `enabled`, `allowedRoles` and `permissions` from a
*governance policy* — a per-tool override store this runtime does not have. A registered tool is
therefore `enabled`, its role list empty and its permissions `null`, which is what "nothing was
declared" looks like here; inventing values would put a policy on a console that nobody set. Serving
those fields' real sources is what a governance-policy endpoint would be for.

**`ai-tool-inventory` 里有三个字段在本运行时没有来源，它们答默认值、而不是猜一个。** 参照实现用**治理策略**
（一个本运行时没有的逐工具覆盖存储）去填 `enabled`、`allowedRoles` 与 `permissions`。所以这里：已注册的工具就是
`enabled`、角色列表为空、权限为 `null` —— 那就是「什么都没声明」在本运行时的样子；**编造取值**等于把一份**没人设过**
的策略显示到控制台上。要让这三个字段有真来源，正是**策略端点**要干的事。

**A trace here holds the conversation's turns, and says so about the rest.** The reference's decision
trace also carries the tool calls, the confirmation decisions and their side effects. This runtime
cannot place them: its audit rows and its side-effect rows carry **no conversation id**, so nothing links
a call to the conversation that proposed it, and the same is true of a confirmation row. The trace
therefore answers the turns as `input` / `assistant` steps and adds **one `notice`** saying the
governance half is unlinked here — a step type the object declares for exactly this kind of statement.
The alternative was an empty list, and an empty list on a page that is *supposed* to show tool calls
reads as "nothing happened" rather than as "nothing is recorded". A test holds the notice in place so it
cannot go quiet; retiring it means recording the link, which is its own change.

**这里的追踪装的是会话的回合，并对其余部分把话说清。** 参照实现的决策轨迹还带工具调用、确认裁决与它们的副作用。
本运行时放不进去：它的**审计行与副作用行都不带会话 id**，于是**没有任何东西**把一次调用拴回提议它的那条会话 ——
确认行也一样。所以追踪把回合答成 `input` / `assistant` 步，并**加一条 `notice`** 说明治理那一半在这里没有链 ——
那是该对象**为这类声明**保留的一个步类型。另一种选择是一个空列表，而在一个**本该**显示工具调用的页上，空列表读起来
是「什么都没发生」、不是「什么都没记」。有测试把这条 notice 钉住、不许它悄声消失；要撤掉它，就得**把那条链记下来**，
那是另一件改动。

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
| `evidence-package` | no evidence-root export exists here |
| `side-effect-revoke` read as the **governance view** | no governance-view endpoint; and this runtime's only local target type is `follow_up`, so a `resultType` of `crm_task` could not hold even if there were one |
| the reference application's tools — `delete_customer` · `create_event` · `query_events` | a tool **inventory** belongs to the **application**, not the contract: this runtime registers `analyze_customer_risk` and `create_followup`. The packs declare the tools they assume (the pack's `tools`), so "this runtime lacks that tool" is readable off the corpus. **⚠ 2026-10-13, corrected in place (original kept):** the reason as written — "a tool inventory belongs to the application, not the contract" — **no longer holds literally**, because the contract **freezes** an `ai-tool-inventory` **object** and this runtime now **serves** it (see the table above). **What still holds is the other half**: *which tools exist* is the application's, and the contract freezes the **shape**, not the **list** |
| `confirm-decision-body` | a **request** body — the corpus names it for callers. There is nothing here to *produce*, so "does this runtime serve it" is not a question with a yes or a no |
| `api-error-code` | a **vocabulary**, not a payload. No endpoint emits it; the check it would support is a different one (are the codes this runtime *does* emit inside the list), which is not conformance to an object |
| `mcp-tool-list` | nothing is emitted here: `keelbase4j-mcp`'s `McpServerTools.listTools()` is this runtime **consuming** a tool list from an external server, not publishing one |

**These three were moved out of "the objects this runtime produces" by decision, not discovered to be missing.** The map in `KeelBase-Private/KeelBase4J/JV-46-覆盖图_2026-10-09.md` had listed them as remaining positions to read (§9.4); reading each one showed that the registry sources the objects this runtime *does* answer at the reference implementation — so a row saying "not produced here" is the honest form, and the alternative (making this runtime emit them) is a product change this runtime has not chosen.

**那条「工具清单属于应用、不属于契约」的读法，2026-10-13 就地更正。** 它**字面上已不成立**：契约**冻着一个**
`ai-tool-inventory` **对象**，而本运行时现在也**服务它**（见上面那张表）。**仍然成立的是另一半**：**哪些工具存在**
是应用的，契约冻的是**形状**、不是**清单** —— 所以那一行说的「参照实现的这几个工具名在这里没有」仍然对。

## Scope

The table covers the objects the corpus asserts about. It is deliberately not the whole registry — a
row here means "the corpus may name this and we answer it", and a missing row means we do not. A tool
call is reached by a **message the planner routes** rather than by a tool name (`ScenarioReplayTest`
carries the routing table), which is the one place this runtime's mapping is not a plain path.
