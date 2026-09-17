# KeelBase4J

Java runtime for **KeelBase** — a conformance implementation of the KeelBase AI Governance Protocol.

> **Status: Phase-0 Spike (G0–G3) — feasibility probe.** The repository holds the protocol
> conformance layer, a minimal governed runtime, and a generator whose output is real, editable
> Spring Boot source. Positioning is **not** decided here — see "Scope & status" below.

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
| **G0** | Reproduce the frozen protocol vectors (canonical JSON / audit hash chain / delegation token / risk levels / governance binding / confirmation lifecycle / failure semantics) | ✅ **7/7 vectors** |
| **G1** | Runtime core + trust loop (Identity → Permission → Governance → Confirmation → Audit → Revoke) | ✅ **this repo** |
| **G2** | Generator: NL → Business Spec → real Spring Boot source | ✅ **this repo** |
| G3 | Changeability (semantic change → code change → migration → tests) | ✅ **this repo** |

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
| `ConfirmationLifecycleTest` | `confirmation-lifecycle-v1-vector.json` | state set, decision set, initial/terminal states, transition table, resolve guards, default TTL, `reject` → `decline` alias |
| `FailureSemanticsTest` | `failure-semantics-v1-vector.json` | each failure disposition driven through the runtime: timeout surfaces as failure, unreachable compensation is never "revoked", empty upstream stays null, duplicate call reuses the effect, duplicate key cannot fork, DB error rethrows, replayed token is rejected, audit fails closed |
| `PermissionWireTest` | the frozen `permission-decision` / `permission-capability-list` / `org-membership-scope` / `authorization` schemas | the Java carriers emit exactly the contract's properties and value domains, and reproduce the reference's wording verbatim |
| `AuthorizationMappingTest` | the same contracts, through the runtime | the decision reproduces the reference's semantics; the capability list is served at `GET /auth/me/permissions` in the frozen shape; row-level access is derived from the decision, not from a role check; the identity projects onto `sub`/`oidcSub` |

Current result: **78/78 green** (`mvn test`).

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
| GET | `/auth/me/permissions` | the caller's capability list, in the frozen `permission-capability-list` contract |

Identity is a thin SPI (`IdentityResolver`): the spike's adapter reads `X-User-Id` / `X-User-Role`
and an optional `X-Oidc-Sub`, and maps it onto the frozen identity contracts (`sub` / `oidcSub` /
`org-membership-scope`). Authorization is **self-built** — `PermissionAuthorizer` reproduces the
reference's ability/condition semantics and emits the frozen `permission-decision` /
`permission-capability-list`, and `OwnershipGuard` derives row-level access from that decision rather
than from a role check. No OPA / Casbin / Cedar, no Keycloak, no `roles`/`permissions` table (the
rules are declared — delivery tier A). See ADR-0004 D4 and the main repo's
`docs/authorization-architecture.md`.

`TrustLoopTest` walks the whole loop over HTTP: read auto-executes → write is gated (nothing
written) → approve executes and records a side effect → the audit chain verifies → revoke soft-
deletes → cross-user access is 403 with no side effect. Because it runs over HTTP against a
standalone app, it is the S3 evidence too: no generator involved.

### G2 — the generator

`BusinessSpecParser` (S1) turns a natural-language business request into a `BusinessSpec`; `JavaGenerator`
(S2) turns that spec into an ordinary Spring Boot project:

```
Business description
   → BusinessSpecParser → BusinessSpec        (entities, fields, ownership rule, AI tools + risk)
   → JavaGenerator      → Spring Boot project  (real .java sources + pom.xml + README)
```

The output is real, editable source — a Maven project that builds on its own. The generated
`GovernanceGate` reads the risk level through the **frozen protocol** (`cn.com.keelbase.protocol.RiskLevel`),
so a generated app makes the same decisions the protocol defines. The same goes for access: the
generated app carries the runtime's identity seam (`IdentityResolver` + a header adapter) and its
`AuthorizationRules` / `PermissionAuthorizer` / `OwnershipGuard` pair, so a spec's policy reaches the
caller as the frozen `permission-decision` / `permission-capability-list` data — served at
`GET /auth/me/permissions` — rather than as a role string compared inside a controller. A spec that
reserves an action for one role is generated as the *absence* of that action from the other roles.

`GeneratorTest` asserts the spec is complete (S1) and that the generated project **compiles** (S2),
checked with the JDK compiler against the current classpath.

The generated app is **self-contained** — it carries its own governance wiring and depends only on
the frozen protocol *library* (risk levels, canonical JSON, audit chain), never on a KeelBase4J
runtime service. So it builds and runs on its own (S3 axis A) and needs no KeelBase4J service at
all (S3 axis B). To see the whole loop end to end on the *generated* artifact:

```bash
bash scripts/demo-generated-app.sh
```

It generates the project, builds it into a runnable jar, starts it, and checks that a read tool
auto-executes, a write tool is gated, approving executes and records a side effect, the audit chain
verifies, and revoke marks the effect revoked.

### G3 — a change carries the data

A change is not a re-create. The generated project owns its schema through **Flyway**: the generator
emits a versioned baseline migration, and a change emits a **new, additive** one — an applied
migration is never rewritten, because Flyway records its checksum. `spring.jpa.hibernate.ddl-auto` is
`validate`, so Hibernate checks the entities against what Flyway built instead of mutating the schema
behind its back. The database is **file-backed** rather than in-memory precisely so the promise is
testable at all: an in-memory one loses the rows before a change could be made to carry them.

```bash
bash scripts/demo-migration.sh
```

It generates v1, runs the app, writes a row, applies a change, rebuilds on the **same** database, and
checks the row is still there with the added column — while the baseline migration stayed
byte-identical and the second run rolled forward exactly one version.

### G3 — regeneration merges the developer's edits

Regeneration is a **three-way merge**, not an overwrite: what the generator produced last time
(recorded under `.keelbase/baseline/`), what the file is now, and what it would produce this time. An
edit **anywhere** in a file survives — the `user-code` marker block the generated sources carry is now
only a suggestion of where code is least likely to collide, not the mechanism. A region both sides
changed is left with `diff3` conflict markers and **reported** by the generator rather than resolved by
a guess; a file the generator never produced is left untouched and reported rather than replaced.

```bash
bash scripts/demo-changeability.sh
```

It hand-edits the generated entity *outside* the marker block — the edit the old mechanism silently
discarded — and checks it is still there after the change, next to the new field.

This is a **line-based** merge: it merges text, not meaning. It keeps two changes that touch different
regions; it cannot tell that renaming a method and updating its call sites is a single change. That
would need the program's structure rather than its lines, and is not attempted here.

## Protocol sources

The authoritative protocol lives in the main repository:

- `docs/protocols/ai-governance-protocol.md` — the protocol (§2 chain / §3 token / §4 risk levels);
- `Server-NestJS/specs/protocol/` — the machine-verifiable vectors and wire schemas;
- §5.1 — how an implementation self-certifies against these vectors.

The copy under `conformance/vectors/` is a read-only snapshot; the main repo remains the source of
truth. See `conformance/vectors/README.md`.

## License

Apache-2.0.
