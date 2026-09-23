# Frozen contract (vendored snapshot)

This directory is a **read-only snapshot** of the protocol contract from the main KeelBase
repository — the vectors, the wire schemas they belong to, and the behaviour-level scenario packs
whose `replay` is the neutral-replay corpus:

```
KeelBase/Server-NestJS/specs/protocol/          → this directory
├── canonical-json-v1-vector.json
├── audit-hash-v1-vector.json
├── delegation-token-v1-vector.json
├── risk-level-v1-vector.json
├── governance-binding-v1-vector.json
├── confirmation-lifecycle-v1-vector.json
├── confirmation-lifecycle-v2-vector.json
├── failure-semantics-v1-vector.json
├── wire-schema-registry.json           ← contract id → schema file
└── schemas/                            ← v1 / v2 / v3 (+ samples)

KeelBase/Server-NestJS/specs/scenarios/         → scenarios/
├── golden-application-v1.json          ← the packs' `replay` (conformance-profile §2.4)
├── trust-proof-v1.json
├── cross-entry-v1.json
├── security-showcase-v1.json           ← `replay: null` (out of replay scope)
├── failure-path-v1.json                ← `replay: null` (fault injection, not a wire request)
├── demo-intent-v1.json
└── replay.schema.json                  ← the replay grammar (opt-in per pack)
```

**Source of truth stays in the main repo.** The main repo's CI keeps these evergreen (gold-sample
regeneration diff + conformance). This copy exists so the Java project is self-contained and
buildable offline.

## Rules

- **Do not hand-edit.** To refresh, run `scripts/sync-vectors.sh [MAIN_REPO_DIR]` — it copies every
  `*-vector.json`, `wire-schema-registry.json` and `schemas/**/*.json` from the main repo, plus every
  `*.json` under `specs/scenarios/`, and normalises line endings to LF.
- A protocol change is made in the main repo first (vector → then implementations), never here.
  See `docs/manual/semantic-change-checklist.md` in the main repo.
- The vector files intentionally contain **no timestamps** — deterministic and diff-able.

**Corresponds to `keelbase-contract` `v1.0.0`** — every vendored file verified byte-identical against
the contract repository (2026-09-21; `sync-vectors.sh --check` prints the current file count, which is
why this line does not carry one). This is the version to cite; the contract is the source from here on.

Copied: 2026-09-20 from `Server-NestJS/specs/protocol/` (main repo `79f32a49`) via
`scripts/sync-vectors.sh`. The main repo is **still the refresh source** — the contract repository has
no remote yet; phase 3 of ADR-0007 repoints this at the contract.

> **Coverage note**: the snapshot mirrors the main repo's protocol directory, so it can hold a
> vector or schema that no Java test consumes. `confirmation-lifecycle-v1` is one: the lifecycle is
> consumed at **v2** now, and v1 is kept here as protocol history.
>
> | Contract file | Java consumer |
> |---|---|
> | `canonical-json-v1` | `CanonicalJsonTest` |
> | `audit-hash-v1` | `AuditChainTest` |
> | `delegation-token-v1` | `DelegationTokenTest` |
> | `risk-level-v1` | `RiskLevelTest` |
> | `governance-binding-v1` | `GovernanceBindingTest` |
> | `confirmation-lifecycle-v1` | — (superseded by v2; kept as protocol history) |
> | `confirmation-lifecycle-v2` | `ConfirmationLifecycleTest` + `ConfirmationLifecycle` |
> | `failure-semantics-v1` | `FailureSemanticsTest` |
> | `wire-schema-registry.json` + `schemas/` | `PermissionWireTest` — the wire carriers are checked **against the schema**, not against a transcription of it: registry → schema file → `required` / `properties` / `additionalProperties` / `enum`, recursing into nested objects, array items and `$ref` |
> | `scenarios/` | `ScenarioReplayTest` — the second carrier replays the packs' `replay` over HTTP and asserts their `expect` (JV-15 Slice 1). Entries this runtime cannot serve are classified and asserted, never skipped; three of the five packs are in replay scope (`security-showcase` and `failure-path` carry `replay: null` by ruling) |
>
> One `failure-semantics-v1` invariant is reproduced only in part — the runtime has no surface for
> the rest. The specific boundary is listed in `FailureSemanticsTest`'s class comment.
