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
| `audit-chain-verification` | `GET /api/v1/audit/verify` | `{valid, checked, brokenIndex}` — the frozen object without `chain[]` |
| `side-effect-revoke` — an effect | `GET /api/v1/ai/tool-effects` | list envelope `{total, page, limit, items}` |
| `side-effect-revoke` — revoke result | `DELETE /api/v1/ai/tool-effects/{id}` | `{effectId, resultType, revokeClass, revokeStatus, revoked}` |
| `permission-capability-list` | `GET /api/v1/auth/me/permissions` | what this identity may do, and on what basis |
| `capabilities` | `GET /api/v1/app/capabilities` | |
| `app-provenance` | `GET /api/v1/app/provenance` | |

**Two answers are this runtime's own object rather than the frozen one** — they are mappings, not
divergences, and they are declared in `docs/ARCHITECTURE.md` §3.2.1: a tool call's result
(`POST /api/v1/ai/chat`, either shape) is an `ExecutionOutcome` and not `tool-invocation`, and the
approve response is an `ExecutionOutcome` and not `confirmation-decision`. The facts a corpus
expectation reads are carried on `status` (`executed` ⇔ `status=executed`; `requiresConfirmation` ⇔
`status=pending_confirmation`).

**What is *not* settled about the chat answer, stated rather than left to be discovered.** Calling it a
mapping says what this runtime does; it does not say the two sides agree, and on this surface they do
not. The contract files `chat-response.schema.json` under the title *"AI 对话响应 POST /ai/chat data"*
with `additionalProperties: false`, and the protocol prose in the main repository is blunter still —
*"非流式 `POST /ai/chat` 不返回确认 token"*, writes go through the streaming channel. This runtime
answers the opposite: the outcome — `status`, `token`, `effectId`, `error` — sits on that same level,
and a non-streaming call is a way to propose a write. Both cannot be true of one surface, and which one
gives way is a decision that spans the two lines rather than a change to make here: conforming would
reach the main repository's `golden-path.e2e.spec.ts`, which reads exactly those fields off this
runtime's answer. What is *not* in doubt is that the corpus is satisfied either way — it judges a tool
call by the facts `executed` / `requiresConfirmation` and never names `status`, `token` or `effectId`.

**本仓在聊天这条面上「没定的」那一半，写出来而不是留给别人去发现。** 说它是**映射**，说的是本运行时
**做了什么**；它**没有**说两侧**一致** —— 而在这条面上它们并不一致。契约把 `chat-response.schema.json`
登记在标题「*AI 对话响应 POST /ai/chat data*」之下、并写着 `additionalProperties: false`；主仓的协议散文
更直白 —— 「**非流式 `POST /ai/chat` 不返回确认 token**」，写操作走**流式**通道。而本运行时答的是**相反**的：
`status`、`token`、`effectId`、`error` 就摊在**同一层**上，一次非流式调用**就是**提出一次写的方式。
两句话不可能同时对**同一条面**成立，而**哪一句让步**是一个**跨两条线的决定**、不是这里能改的：
若要符合，会一路够到主仓的 `golden-path.e2e.spec.ts` —— 它读的正是本运行时这条答案上的那几个字段。
**不存疑的一点是**：语料**两种都满意** —— 它按 `executed` / `requiresConfirmation` 这两个**事实**判一次工具调用，
**从不点名** `status`、`token` 或 `effectId`。

## Not served

An object below is one the corpus names and this runtime **does not expose** — a **"not applicable"**,
not a mapping to fix. (The same list, from the runner's side, is the classification its inventory test
asserts.)

| wire object | why not |
|---|---|
| `permission-decision` | the frozen decision is computed (`PermissionAuthorizer.decide`) but only the capability list is on the wire — there is no decision-over-action×subject endpoint |
| `evidence-package` | no evidence-root export exists here |
| `side-effect-revoke` read as the **governance view** | no governance-view endpoint; and this runtime's only local target type is `follow_up`, so a `resultType` of `crm_task` could not hold even if there were one |
| the reference application's tools — `delete_customer` · `create_event` · `query_events` | a tool inventory belongs to the **application**, not the contract: this runtime registers `analyze_customer_risk` and `create_followup`. The packs declare the tools they assume (the pack's `tools`), so "this runtime lacks that tool" is readable off the corpus |

## Scope

The table covers the objects the corpus asserts about. It is deliberately not the whole registry — a
row here means "the corpus may name this and we answer it", and a missing row means we do not. A tool
call is reached by a **message the planner routes** rather than by a tool name (`ScenarioReplayTest`
carries the routing table), which is the one place this runtime's mapping is not a plain path.
