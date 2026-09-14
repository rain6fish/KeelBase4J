# KeelBase4J

Java runtime for **KeelBase** — a conformance implementation of the KeelBase AI Governance Protocol.

> **Status: Phase-0 Spike (G0) — feasibility probe.** This repository currently holds only the
> protocol conformance layer. It does **not** yet implement a runtime, generator, or application.
> See "Scope & status" below.

## What this is

KeelBase (reference implementation in TypeScript, `rain6fish/KeelBase`) defines a language-neutral
AI-governance protocol — the audit hash chain, the delegation token, and tool risk levels. This
repository is the **Java/Spring side** that implements the same protocol so that Java enterprise
applications can live inside the same governance boundary.

The design rule is **implement the frozen contract, do not translate the reference**:

- the protocol is the source of truth, not the TypeScript code;
- compatibility is proven by reproducing the language-neutral vectors that live in
  `specs/protocol/` of the main repo — the same files any other implementation would use.

## Scope & status

| Phase | What | Status |
|---|---|---|
| **G0** | Reproduce the frozen protocol vectors (canonical JSON / audit hash chain / delegation token / risk levels / governance binding) | ✅ **42/42** |
| **G1** | Runtime core + trust loop (Identity → Permission → Governance → Confirmation → Audit → Revoke) | ✅ **this repo** |
| G2 | Generator: NL → Business Spec → Application Model → real Java/Spring source | ⬜ |
| G3 | Changeability (semantic change → code change → migration → tests) | ⬜ |

G0 is a **feasibility probe**, not a product positioning decision. The Spike's success criteria
(S1–S5) and its guardrails are recorded in the private planning repo; this codebase only claims
what its tests prove.

## Build & run

Requires JDK 17+ and Maven. Runtime code has **no third-party dependencies** (JDK only); JUnit is
test-scope.

```bash
mvn test
```

The conformance suite reads the frozen vectors from `conformance/vectors`
(overridable via `-Dkeelbase.vectors.dir=<dir>`).

### What the suite proves

| Test | Vector | Checks |
|---|---|---|
| `CanonicalJsonTest` | `canonical-json-v1-vector.json` | byte-faithful `JSON.stringify(payload, sortedKeys)` — nested replacer filtering, UTF-16 key order, JS number/string formatting |
| `AuditChainTest` | `audit-hash-v1-vector.json` | chain hash, legacy key derivation, genesis literal, chain verify, tamper/rotation/domain-separation |
| `DelegationTokenTest` | `delegation-token-v1-vector.json` | JWT HS256 sign/verify, `aud` scoping, expiry, tamper detection, `sub` semantics |
| `RiskLevelTest` | `risk-level-v1-vector.json` | risk-strategy table + derivation (write → R3, read → R1) |
| `GovernanceBindingTest` | `governance-binding-v1-vector.json` | strategy → gate outcome, denial-reason vocabulary |

Current result: **42/42 green**.

CI (`.github/workflows/ci.yml`) runs the same suite on every push/PR, plus a *vector-drift* check
that the vendored vectors still match the authoritative copy in the main repo — the snapshot here
must never be hand-edited.

### G1 — runtime and the trust loop

A minimal Spring Boot app (`KeelBase4JApplication`) whose AI operations run only inside the trust
boundary. Tools declare a risk level; the runtime — not a prompt — enforces what may run:

| Tool | Risk | Behaviour |
|---|---|---|
| `analyze_customer_risk` | R1 | executes immediately (read) |
| `create_followup` | R3 | **not executed** until a human approves |

Endpoints (`X-User-Id` / `X-User-Role` headers carry the principal in the spike):

| Method | Path | Purpose |
|---|---|---|
| POST | `/ai/chat` | deterministic intent router → governed tool call |
| POST | `/ai/confirmations/{token}` | `approve` (executes) or `decline` (writes nothing) |
| GET | `/ai/tool-effects` | list recorded side effects |
| DELETE | `/ai/tool-effects/{id}` | revoke → local compensation (soft delete) |
| GET | `/audit/verify` | recompute and verify the audit hash chain |

`TrustLoopTest` walks the whole loop over HTTP: read auto-executes → write is gated (nothing
written) → approve executes and records a side effect → the audit chain verifies → revoke soft-
deletes → cross-user access is 403 with no side effect. Because it runs over HTTP against a
standalone app, it is the S3 evidence too: no generator involved.

## Protocol sources

The authoritative protocol lives in the main repository:

- `docs/protocols/ai-governance-protocol.md` — the protocol (§2 chain / §3 token / §4 risk levels);
- `Server-NestJS/specs/protocol/` — the machine-verifiable vectors and wire schemas;
- §5.1 — how an implementation self-certifies against these vectors.

The copy under `conformance/vectors/` is a read-only snapshot; the main repo remains the source of
truth. See `conformance/vectors/README.md`.

## License

Apache-2.0.
