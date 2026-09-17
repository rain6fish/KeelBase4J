# KeelBase4J — Architecture & Components

> Phase-0 spike (feasibility probe). This document describes what exists **today** in this
> repository: its subsystems, their responsibilities, and the exact component versions.
> Status legend: ✅ done · ⬜ not started.

---

## 1. What this is

KeelBase4J is the **Java runtime for KeelBase**. The KeelBase reference implementation (TypeScript,
`rain6fish/KeelBase`) defines a language-neutral **AI Governance Protocol** — an audit hash chain, a
delegation token, and tool risk levels. This repository implements the same protocol in Java, so a
Java/Spring application can live inside the same governance boundary and be proven compatible by
reproducing the same frozen vectors.

**The design rule**: implement the frozen contract; do not translate the reference implementation.
The protocol (`docs/protocols/ai-governance-protocol.md` in the main repo) and its machine-readable
vectors (`Server-NestJS/specs/protocol/`) are the source of truth.

---

## 2. Relationship to the KeelBase main repository

| Concern | Lives in | Consumed by this repo as |
|---|---|---|
| Protocol semantics | main repo `docs/protocols/ai-governance-protocol.md` | the specification to implement |
| Frozen vectors + wire schemas | main repo `Server-NestJS/specs/protocol/` | vendored read-only snapshot in `conformance/vectors/` |
| Conformance evidence | this repo `mvn test` (78 assertions) | proves cross-runtime parity (CE-1 role ③) |

The vendored vectors are a snapshot; the main repo stays authoritative. CI job `vector-drift` diffs
them so the snapshot cannot silently diverge.

---

## 3. Subsystems

### 3.1 `cn.com.keelbase.protocol` — the protocol library (G0 ✅)

Pure Java, **zero third-party runtime dependencies** (JDK crypto only). Language-neutral semantics.

| Class | Responsibility |
|---|---|
| `Json` | minimal JSON parser (map/list/scalar model) |
| `CanonicalJson` | byte-faithful `JSON.stringify(payload, sortedKeys)` — nested replacer filtering, UTF-16 key order, ECMAScript number formatting |
| `AuditChain` | §2 chain hash (`prevHash ?? "genesis"`), legacy key derivation, chain verify across candidate keys |
| `DelegationToken` | §3 JWT HS256 sign/verify with `aud`/`iss`/`exp`/`sub` |
| `RiskLevel` | §4 risk-strategy table + derivation |
| `GovernanceBinding` | §4 strategy → gate outcome + denial-reason vocabulary |
| `ConfirmationLifecycle` | the frozen confirmation state machine: states, decisions, transitions, guards, default TTL |
| `PermissionDecision` | carrier of the frozen `permission-decision` wire contract |
| `PermissionCapabilityList` | carrier of the frozen `permission-capability-list` wire contract |
| `OrgMembershipScope` | carrier of the frozen `org-membership-scope` wire contract |
| `AuthorizationReasons` | carrier of the frozen `authorization` wire contract (why a tool call was allowed / refused) |

### 3.2 `cn.com.keelbase.runtime` — the runtime core (G1 ✅)

The hand-written reference runtime: a Spring Boot app whose AI operations run only inside the trust
boundary.

| Package | Responsibility |
|---|---|
| `domain` | `Customer` / `FollowUp` entities + repositories (own-scope, soft-deletable) |
| `identity` | `Principal` (wire-shaped identity) + `IdentityResolver` SPI + `IdentityEvidence` + `HeaderIdentityResolver` (the spike's adapter) |
| `authz` | `AuthorizationRules` (declared role → capability) · `PermissionAuthorizer` (self-built decision function → frozen `permission-decision` / `permission-capability-list`) · `OwnershipGuard` (row-level enforcement, driven by the decision) |
| `tool` | `AiTool` contract, `ToolRegistry`, the two AI tools (R1 read / R3 write) |
| `governance` | `GovernanceService` (risk → gate), confirmation store + entity |
| `effect` | `SideEffect` + record/revoke (content-derived idempotency, class-aware revoke) |
| `audit` | `AuditService` — hash-chained AI audit + verify |
| `engine` | `GovernedExecutionEngine` — the loop: gate → confirm → execute → audit → effect |
| `web` | REST: `/ai/chat`, confirmations, tool-effects, `/audit/verify`, `/auth/me/permissions` |

### 3.3 `cn.com.keelbase.gen` — the generator (G2 ✅)

| Class | Responsibility |
|---|---|
| `BusinessSpec` | the spec model: entities, fields, ownership rule, AI tools |
| `BusinessSpecParser` | S1 — natural-language request → `BusinessSpec` |
| `JavaGenerator` | S2 — spec → a self-contained Spring Boot project (real source) |
| `GeneratorMain` | dev/CI entry point for the generator |

The generated project is **self-contained**: it carries its own governance wiring and depends only on
the protocol *library*, never on a KeelBase4J runtime service (S3 axes A and B).

### 3.4 The generated application

```
<module>/
├── pom.xml · README.md
├── .keelbase/baseline/   what the generator produced last time — the base a regeneration merges against
└── src/main/
    ├── java/com/example/<module>/
    │   ├── Application.java
    │   ├── domain/   <Entity>.java · <Entity>Repository.java
    │   ├── identity/ Principal · IdentityEvidence · IdentityResolver · HeaderIdentityResolver
    │   ├── authz/    AuthorizationRules · PermissionAuthorizer · OwnershipGuard
    │   ├── ai/       AiTool · GovernanceGate · ToolRegistry · GovernanceEngine
    │   │             ConfirmationStore · SideEffectStore · AuditChainStore · <Tool>Tool
    │   └── web/      AiController · AuthController · GovernanceController · <Entity>Controller
    └── resources/ application.properties
                    db/migration/  V1__<module>_baseline.sql · V<n>__add_*.sql  (Flyway owns the schema)
```

---

## 4. The trust loop (the load-bearing flow)

```text
request (identity) ─► tool selected ─► gate(risk level)
        │                                   │
        │            ALLOW ────────────────►├─► execute ─► side effect ─► audit
        │            CONFIRM ──► token ──► pending (nothing written)
        │                                   │        └─ approve ─► execute ─► side effect ─► audit
        │            BLOCK ────────────────►└─► denied (never executed)
        ▼
   revoke ─► local compensation (soft delete) ─► effect status revoked
   verify ─► recompute the audit hash chain
```

Every step is enforced by the runtime, not described in a prompt.

---

## 5. Components & versions

### Toolchain

| Component | Version |
|---|---|
| Java (Temurin JDK) | **17.0.20.1** (`--release 17`) |
| Apache Maven | **3.9.16** |
| Spring Boot (parent) | **3.2.5** |

### Runtime dependencies (resolved)

| Component | Version | Role |
|---|---|---|
| Spring Framework | 6.1.6 | core / context / web / tx |
| Spring Boot starters | 3.2.5 | `web`, `data-jpa`, `test` |
| Spring Data JPA | 3.2.5 | repositories |
| Hibernate ORM | 6.4.4.Final | JPA provider |
| Jakarta Persistence API | 3.1.0 | entity annotations |
| HikariCP | 5.0.1 | connection pool |
| Embedded Tomcat | 10.1.20 | servlet container (`spring-boot-starter-web`) |
| H2 Database | 2.2.224 | in-memory DB (spike) |
| Jackson | 2.15.4 | JSON (web layer) |
| SLF4J + Logback | 2.0.x / 1.4.14 | logging |
| Micrometer | 1.12.5 | observability (transitive) |

### Test dependencies

| Component | Version |
|---|---|
| JUnit Jupiter | 5.10.2 |
| Mockito | 5.7.0 |
| AssertJ | 3.24.2 |
| JSONPath | 2.9.0 |
| XMLUnit | 2.9.1 |

### Cryptography

No third-party crypto: the JDK's `javax.crypto` (HMAC-SHA256) and `java.security.MessageDigest`
(SHA-256) implement the protocol. This keeps the protocol layer dependency-free and independently
verifiable.

### Own artifacts

| Artifact | Version | Contents |
|---|---|---|
| `cn.com.keelbase:keelbase4j-protocol` | 0.1.0-SNAPSHOT | `protocol` + `runtime` + `gen` packages. Published as a **library jar** (classes at the root) alongside a runnable boot jar with the `exec` classifier. |

---

## 5b. Authorization & identity (decision: ADR-0004 D3/D4)

- **Security ≠ Trust.** Spring Security answers *"who is this request"* (authentication); KeelBase
  answers *"may this AI action happen under enterprise rules"* (authorization / policy / confirmation
  / audit / revoke). They are separate concerns.
- **Don't own identity infrastructure; own enterprise authorization semantics.** Identity is a
  **pluggable adapter** (OIDC/OAuth2 as the protocol entry; Keycloak is one reference adapter, not a
  hard dependency). In this repo the seam is the single-method `IdentityResolver` SPI: the adapter
  maps whatever evidence its deployment offers (`IdentityEvidence` — request headers in the spike,
  validated OIDC/JWT claims or an LDAP bind result later) to a wire-shaped `Principal`. Swapping the
  adapter touches nothing else; exactly one implementation must be a bean.
- **No new "identity contract".** Authorization semantics map onto the wire contracts already frozen
  in the KeelBase main repo — `authorization`, `permission-decision`, `permission-capability-list`,
  `org-member-item`, `org-membership-scope`, `delegation-token-claims`. Each is carried in
  `cn.com.keelbase.protocol` as an explicit `toWire()` shape, and `Principal` projects onto it
  (`subject()` = the `delegation-token-claims` `sub`; `org()` = `org-membership-scope`).
- **The decision function is self-built.** `PermissionAuthorizer` reproduces the reference's
  ability/condition semantics and emits the frozen `permission-decision` / `permission-capability-list`
  — same shape, same closed values, same wording — so a shared frontend reads the same thing from
  either runtime (Rev-8). No OPA / Casbin / Cedar is embedded: the engine would buy the easy part
  (boolean evaluation) while costing a parallel contract (ADR-0004 Option E). Rules come from
  `AuthorizationRules`, which is *declared* (delivery tier A: no `roles`/`permissions` table); tier B
  swaps the rule source and leaves the decision function untouched.
- **Row-level permission is derived, not hardcoded.** `OwnershipGuard` asks the authorizer for the
  decision and the capability's scope; `scope=all` reaches any row, `scope=own` requires ownership.
  The cross-user 403 is therefore a consequence of the same contract either runtime serves.
- **Served surface:** `GET /auth/me/permissions` returns the frozen `permission-capability-list` at
  the same path and in the same shape as the reference (PC-1) — the single capability source a
  frontend keys page/menu/button visibility off.
- **Spike scope:** no Keycloak, no unified permission console, no Spring Security wiring (parked —
  authentication is the request-entry layer, `docs/authorization-architecture.md` §7.1). Organization
  data ranges are carried on the identity but not enforced at row level (that is tier B; the spike's
  row scope is `own`). Generated apps still carry their own header seam — the runtime SPI and the
  generated seam are **not** yet unified.

## 6. Build & verification

```bash
mvn test                              # conformance (57) + runtime (18, incl. the trust loop) + generator (3)
mvn -DskipTests install               # install the protocol library into the local repo
bash scripts/demo-generated-app.sh    # generate → build → run → exercise the generated app
```

CI (`.github/workflows/ci.yml`): `conformance` (JDK 17, `mvn verify`) + `vector-drift`
(diff the vendored vectors against the main repo).

---

## 7. Status

| Phase | Scope | Status |
|---|---|---|
| G0 | protocol conformance (7 vectors + the permission/identity wire contracts, 57 assertions) | ✅ |
| G1 | runtime core + trust loop (S3/S4 on a hand-written app) | ✅ |
| G2 | generator: NL → spec → real Spring Boot source (S1/S2) | ✅ |
| G2+ | generated app runs standalone; trust loop holds on the artifact (S3 axes A+B, S4) | ✅ |
| G3 | changeability (S5 — the spike's kill gate): change applied as an additive migration, hand edits preserved, rule enforced, **the data already in the database carried over** | ✅ |

**Not yet in scope (by design):** authentication stack (identity is a pluggable seam), multi-tenancy,
HA, UI, and the KeelBase AI pipeline (agent / RAG / memory) — the latter belongs to a Java AI
framework, not to the runtime.
