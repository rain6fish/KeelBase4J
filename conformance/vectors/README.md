# Frozen protocol vectors (vendored snapshot)

These JSON files are a **read-only snapshot** of the language-neutral conformance vectors from the
main KeelBase repository:

```
KeelBase/Server-NestJS/specs/protocol/
├── canonical-json-v1-vector.json
├── audit-hash-v1-vector.json
├── delegation-token-v1-vector.json
├── risk-level-v1-vector.json
├── governance-binding-v1-vector.json
├── confirmation-lifecycle-v1-vector.json
└── failure-semantics-v1-vector.json
```

**Source of truth stays in the main repo.** The main repo's CI keeps these vectors evergreen
(gold-sample regeneration diff + conformance). This copy exists so the Java project is
self-contained and buildable offline.

## Rules

- **Do not hand-edit.** To refresh, run `scripts/sync-vectors.sh [MAIN_REPO_DIR]` — it copies every
  `*-vector.json` from the main repo and normalises line endings to LF.
- A protocol change is made in the main repo first (vector → then implementations), never here.
  See `docs/manual/semantic-change-checklist.md` in the main repo.
- The vector files intentionally contain **no timestamps** — deterministic and diff-able.

Copied: 2026-09-15 from `Server-NestJS/specs/protocol/` (main repo `a434292e`, vector version `v1`)
via `scripts/sync-vectors.sh`.

> **Coverage note**: every vendored vector is consumed by a Java test class — the snapshot and the
> conformance coverage are both complete.
>
> | Vector | Java consumer |
> |---|---|
> | `canonical-json-v1` | `CanonicalJsonTest` |
> | `audit-hash-v1` | `AuditChainTest` |
> | `delegation-token-v1` | `DelegationTokenTest` |
> | `risk-level-v1` | `RiskLevelTest` |
> | `governance-binding-v1` | `GovernanceBindingTest` |
> | `confirmation-lifecycle-v1` | `ConfirmationLifecycleTest` + `ConfirmationLifecycle` |
> | `failure-semantics-v1` | `FailureSemanticsTest` |
>
> One `failure-semantics-v1` invariant is reproduced only in part — the spike has no surface for the
> rest; it is listed with its reason in `FailureSemanticsTest`'s class comment. See
> `KeelBase4J-Spike-报告_2026-09-14.md` §5.
