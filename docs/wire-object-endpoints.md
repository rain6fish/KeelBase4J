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
