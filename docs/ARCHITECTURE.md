# KeelBase4J — Architecture & Components

> This document describes what exists **today** in this repository: its subsystems, their
> responsibilities, and the exact component versions.
> Status legend: ✅ done · ⬜ not started.

---

## 1. What this is

KeelBase4J is the **Java runtime for KeelBase**. The KeelBase reference implementation (TypeScript,
`rain6fish/KeelBase`) defines a language-neutral **AI Governance Protocol** — an audit hash chain, a
delegation token, and tool risk levels. This repository implements the same protocol in Java, so a
Java/Spring application can live inside the same governance boundary and be proven compatible by
reproducing the same frozen vectors.

**The design rule**: implement the frozen contract; do not translate the reference implementation.
The protocol has two halves, and they do not live in the same place: its machine-readable half — the
frozen vectors, the wire schemas and the registry that indexes them — lives in the **contract
repository** (`rain6fish/keelbase-contract`), while the prose half stays in
`docs/protocols/ai-governance-protocol.md` in the main repo. Both are the source of truth.

---

## 2. Relationship to the upstream repositories

| Concern | Lives in | Consumed by this repo as |
|---|---|---|
| Protocol semantics | main repo `docs/protocols/ai-governance-protocol.md` | the specification to implement |
| Frozen vectors + wire schemas | contract repo `rain6fish/keelbase-contract` | vendored read-only snapshot in `conformance/vectors/` |
| Behaviour-level scenario packs (their `replay`) | main repo `Server-NestJS/specs/scenarios/` | vendored read-only snapshot in `conformance/vectors/scenarios/`, replayed over HTTP by `ScenarioReplayTest` (conformance-profile §2.4, Extended layer) |
| Conformance evidence | this repo `mvn test` — the whole suite, green (the CI badge is the live count) | proves cross-runtime parity (CE-1 role ③) |

The vendored vectors are a read-only snapshot and the contract repository is authoritative for them.
CI job `vector-drift` diffs the snapshot against the copy it is refreshed from, so the last link cannot
silently diverge; `conformance/vectors/README.md` states which source that is today, and what the gate
does and does not cover.

---

## 3. Subsystems

### 3.1 `keelbase4j-protocol` — the protocol library (G0 ✅)

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

### 3.2 `keelbase4j-runtime` — the runtime core (G1 ✅)

The hand-written reference runtime: a Spring Boot app whose AI operations run only inside the trust
boundary.

| Package | Responsibility |
|---|---|
| `domain` | `Customer` / `FollowUp` entities + repositories (own-scope, soft-deletable) |
| `identity` | `Principal` (wire-shaped identity) + `IdentityResolver` SPI + `IdentityEvidence` + `AuthenticatedSubject` + `SecurityIdentityResolver` (the default adapter) + `LocalIdentities` (subject → local user/role, tier A) + `CurrentPrincipal` (what the web layer asks) |
| `security` | What answers *who is this request*: `DelegationTokenAuthenticationFilter` (verifies the frozen token) + `SecurityConfig`. **No authorization lives here** — no `hasRole`, no `@PreAuthorize`, no URL rules |
| `authz` | `AuthorizationRules` (declared role → capability) · `PermissionAuthorizer` (self-built decision function → frozen `permission-decision` / `permission-capability-list`) · `OwnershipGuard` (the single enforcement point: coarse gate, then row gate) |
| `scope` | The row range: `ScopeLevel` / `ScopeDescriptor` (internal, never on the wire) · `ScopeFilter` (descriptor → typed predicate, and the object-level check) · `DataScopeRules` (declared level per role) · `Departments` (declared tree) |
| `tool` | `AiTool` contract, `ToolRegistry`, the two AI tools (R1 read / R3 write) |
| `governance` | `GovernanceService` (risk → gate), confirmation store + entity, `ConfirmationSweeper` (the offline window closing), `ConfirmationWatchers` (the in-process registry that carries a decision to an open stream) |
| `effect` | `SideEffect` + record/revoke (content-derived idempotency, class-aware revoke) |
| `audit` | `AuditService` — hash-chained AI audit + verify, appends serialized on a database row lock (`AuditChainHead`) |
| `pipeline` | The AI seam: `ToolCallPlanner` (an SPI a model-driven pipeline implements) · `IntentPlan` (tool + args, and nothing else) · `ChatReplier` (what to say back, the second seam, with `DeterministicReplier` as its default) · `RuleBasedPlanner` + `RuleBasedPlannerAutoConfiguration` (the default, so the loop is reproducible without a model). The default is registered `@ConditionalOnMissingBean`, so a deployment that declares its own planner simply replaces it — no `@Primary`, no exclusion list, no edit here. |
| `engine` | `GovernedExecutionEngine` — the loop: gate → confirm → execute → audit → effect |
| `conversation` | The transcript behind `conversationId`: `ConversationStore` + `ConversationMessage` — turns and nothing more (no embeddings, no retrieval, no memory policy) |
| `web` | REST: chat in both shapes (`/ai/chat`, `/ai/chat/stream` with `/admin/ai/chat/stream` behind the admin role), confirmations, tool-effects, `/audit/verify`, the identity surface (`/auth/me`, `/auth/me/permissions`, `/auth/oauth/providers`, `/auth/login-stats`), `/customers`, `/app/capabilities`, `/app/provenance`. Mapped at the root but mounted under `/api/v1` (`server.servlet.context-path`) — the reference's prefix, which is what lets one runtime-neutral frontend talk to this runtime without rebasing |

### 3.3 `keelbase4j-generator` — the generator (G2 ✅)

| Class | Responsibility |
|---|---|
| `BusinessSpec` | the spec model: entities, fields, ownership rule, AI tools |
| `BusinessSpecParser` | business request → `BusinessSpec`. A deterministic router over the requests it recognises — **not** a model-backed generator (see the README's "What this is not") |
| `JavaGenerator` | spec → a self-contained Spring Boot project (real source) |
| `GeneratorMain` | dev/CI entry point for the generator |

The generated project is **self-contained**: it carries its own governance wiring and depends only on
the protocol *library*, never on a KeelBase4J runtime service — so it builds and runs with no
KeelBase4J deployment present at all.

### 3.4 `keelbase4j-springai` — the model-driven planner (adapter)

| Class | Responsibility |
|---|---|
| `SpringAiToolCallPlanner` | Implements the runtime's `ToolCallPlanner` seam with Spring AI's `ChatClient` |
| `SpringAiPlannerAutoConfiguration` | Puts that planner on the seam — **only** when a model is configured |

This module is an **adapter**, and the arrow points one way: it depends on the runtime; the runtime
does not know it exists (CLAUDE.md 硬规则 6). It carries no provider dependency either — which model
to talk to is a deployment's decision, so adding a provider is what switches it on.

Two properties are worth stating because they are the difference between an adapter and a back door:

- **It proposes; it does not execute.** What it returns is an `IntentPlan` — a tool name and
  arguments. Risk level, confirmation, audit and revoke stay the runtime's, read from the tool's own
  declaration and applied unconditionally downstream.
- **It does not tell the model how a call is governed.** The catalogue sent to the model is names and
  descriptions only; `AiTool`'s governance metadata is marked "never sent to a model", and this is
  where that is enforced rather than asserted — there is a test on the prompt itself.

Registration is conditional on a `ChatClient.Builder` existing, and ordered *before* the runtime's
own planner so the latter stands down. Without a provider the module is inert and the rule-based
planner stays in charge, which is what makes it safe to have a model-shaped dependency at all.

### 3.5 The generated application

```
<module>/
├── pom.xml · README.md
├── .keelbase/baseline/   what the generator produced last time — the base a regeneration merges against
└── src/main/
    ├── java/com/example/<module>/
    │   ├── Application.java
    │   ├── domain/   <Entity>.java · <Entity>Repository.java
    │   ├── identity/ Principal · IdentityEvidence · IdentityResolver · DelegationTokenIdentityResolver
    │   │             LocalIdentities · HeaderIdentityResolver (for deployments behind a guard)
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
| Spring Boot (parent) | **4.1.1** |

### Runtime dependencies (resolved)

| Component | Version | Role |
|---|---|---|
| Spring Framework | 7.0.9 | core / context / web / tx |
| Spring Boot starters | 4.1.1 | `webmvc`, `data-jpa`, `security`, `test` |
| Spring Data JPA | 4.1.1 | repositories |
| Hibernate ORM | 7.4.5.Final | JPA provider |
| Jakarta Persistence API | 3.2.0 | entity annotations |
| HikariCP | 7.0.2 | connection pool |
| Embedded Tomcat | 11.0.24 | servlet container (`spring-boot-starter-webmvc`) |
| H2 Database | 2.4.240 | in-memory DB (spike) |
| Jackson | 3.1.5 (`tools.jackson`; annotations stay 2.21) | JSON (web layer) |
| SLF4J + Logback | 2.0.x / 1.5.38 | logging |
| Micrometer | 1.17.1 | observability (transitive) |
| Spring AI | 2.0.1 | the adapter module's `ChatClient` — **no provider is bound here**; which model to talk to is a deployment's decision |

### Test dependencies

| Component | Version |
|---|---|
| JUnit Jupiter | 6.0.3 |
| Mockito | 5.23.0 |
| AssertJ | 3.27.7 |
| JSONPath | 2.10.0 |
| XMLUnit | 2.11.0 |

### Cryptography

No third-party crypto: the JDK's `javax.crypto` (HMAC-SHA256) and `java.security.MessageDigest`
(SHA-256) implement the protocol. This keeps the protocol layer dependency-free and independently
verifiable.

### Own artifacts

| Artifact | Version | Contents |
|---|---|---|
| `cn.com.keelbase:keelbase4j-protocol` | 0.1.0 | The protocol library — **no third-party dependency**. This is the artifact a generated application depends on, and the one published to Maven Central. |
| `cn.com.keelbase:keelbase4j-runtime` | 0.1.0 | The runtime — a plain jar (usable as a library) plus a runnable boot jar under the `exec` classifier. |
| `cn.com.keelbase:keelbase4j-generator` | 0.1.0 | The generator (studio side). |
| `cn.com.keelbase:keelbase4j-springai` | 0.1.0 | The Spring AI adapter — **depends on the runtime**, and nothing depends on it. |
| `cn.com.keelbase:keelbase4j-demo` | 0.1.0 | The runnable demo deployment — runtime + adapter + one provider, chosen by Maven profile. |
| `cn.com.keelbase:keelbase4j` | 0.1.0 | The parent/aggregator (`pom`). |

Only `keelbase4j-protocol` — together with the parent pom it inherits from — is published: it is the
one a generated application resolves, so it is the one that has to be in a repository. What publishes
is decided by the **reactor**, not by a per-module flag: the release workflow deploys with
`-pl keelbase4j-protocol -am` and checks that the reactor it produces holds exactly those two. A
generated project's dependency on it is not hand-written either: the version is filtered from this
project's own version, so a release moves both together.

The dependency edge is one-way — `runtime` → `protocol`, `generator` → `protocol` — and nothing
depends on the runtime. That is what keeps the runtime the thing under test while adapters (a model
provider, an identity provider) sit outside it.

---

## 5b. Authorization & identity (decision: ADR-0004 D3/D4)

- **Security ≠ Trust.** Spring Security answers *"who is this request"* (authentication); KeelBase
  answers *"may this AI action happen under enterprise rules"* (authorization / policy / confirmation
  / audit / revoke). They are separate concerns.
- **Authentication happens at the entry, and only there.** A caller presents a delegation token as
  `Authorization: Bearer <jwt>`; `DelegationTokenAuthenticationFilter` verifies it with the frozen
  protocol's own `DelegationToken.verify` — deliberately not a second JWT implementation, since two
  verifiers are two things to keep in step. A request that fails that check never reaches a
  controller. `SecurityConfig` then contains **no authorization at all** (ADR-0004 D3): putting role
  rules there would not merely duplicate KeelBase's decision, it would be a second, silently
  diverging answer to the same question. One consequence worth knowing: error dispatches are
  permitted, because the container re-renders a handled failure (a 403 from a controller) on a fresh
  dispatch — demanding authentication again there turns every refusal into a 401.
- **The token proves a subject; the deployment decides what it means.** The frozen token contract has
  no role claim — "delegation never escalates privilege: the mapped local user's own permissions
  apply after mapping" (protocol §3). So `LocalIdentities` maps the verified subject to a local user
  and role (tier A: declared, no user table), and an unknown subject is refused rather than defaulted.
  The adapter this replaced read `X-User-Id` / `X-User-Role` off the request — which let a caller
  grant itself any role. Those headers are now ignored, and `AuthenticationTest` pins that: the same
  request that used to be an administrator is a 401.
- **Don't own identity infrastructure; own enterprise authorization semantics.** Identity is a
  **pluggable adapter** (OIDC/OAuth2 as the protocol entry; Keycloak is one reference adapter, not a
  hard dependency). In this repo the seam is the single-method `IdentityResolver` SPI: the adapter
  maps whatever evidence its deployment offers (`IdentityEvidence` — an authenticated subject today,
  validated OIDC/LDAP claims later) to a wire-shaped `Principal`. Swapping the adapter touches nothing
  else; exactly one implementation must be a bean.
- **No new "identity contract".** Authorization semantics map onto the wire contracts already frozen
  in the contract repository — `authorization`, `permission-decision`, `permission-capability-list`,
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
- **Access to a row is two questions, answered separately.** `OwnershipGuard` is the single enforcement
  point: the coarse gate (`PermissionAuthorizer` — "may this action on this subject happen") and then the
  row gate (`ScopeFilter` — "which rows"). Neither is a role check, so the cross-user 403 is a
  consequence of the contracts rather than something hardcoded.
- **The row range is reproduced from the reference** (`docs/data-scope.spec.md`): the levels are
  `own` / `org` / `own_dept` / `own_dept_and_below` / `custom_dept` / `all`, expressed as an internal
  descriptor and translated into a **typed predicate** — never a SQL string. Two rules are load-bearing:
  missing organization or department facts **tighten** a range to `own` rather than widening it, and an
  entity that is not in the scope registry gets **no** predicate instead of matching everything. The
  range names never reach the wire: `permission-capability-list.scope` stays exactly `all` / `own`, and
  a test pins that on the served shape.
- **Identity carries the facts the range needs.** `org-membership-scope` is no longer empty — the local
  directory maps a subject to an organization and department, which is what lets a range name one.
- **Served surface:** `GET /auth/me/permissions` returns the frozen `permission-capability-list` at
  the same path and in the same shape as the reference (PC-1) — the single capability source a
  frontend keys page/menu/button visibility off.
- **Spike scope:** no Keycloak, no unified permission console. Authentication is in place at the
  request entry with a locally configured token (`docs/authorization-architecture.md` §7.1); a real
  IdP is an adapter behind the same seam, not a redesign. The row range is **in place** — tier B's
  mechanism, reproduced from `docs/data-scope.spec.md` — with the levels **declared** rather than
  configured in a table; making them per-role configurable is the part of tier B that remains, along
  with the management console.
  **Generated apps are a separate story:** they carry their own `IdentityResolver` + header adapter and
  do **not** ship Spring Security, so the runtime's token-authenticated entry and a generated app's
  header seam are **not** unified — a generated app is a self-contained artifact, not this deployment.

## 6. Build & verification

```bash
mvn test                              # protocol · runtime · generator · springai — the whole suite
mvn -DskipTests install               # install every module into the local repo
bash scripts/demo-generated-app.sh    # generate → build → run → exercise the generated app
bash scripts/demo-changeability.sh    # change → regenerate → the hand edit survives
bash scripts/demo-migration.sh        # change → additive migration → existing rows carry over
bash scripts/demo-golden-path.sh      # the frontend's own modules against this runtime (L3)
bash scripts/demo-springai.sh         # a real model on the planner seam (needs a model key)
```

CI (`.github/workflows/ci.yml`): `conformance` (JDK 17, `mvn verify`) + `vector-drift`
(diff the vendored vectors against their refresh source). Publishing runs on a `v*` tag
(`.github/workflows/release.yml`): the parent pom and `keelbase4j-protocol` are signed and uploaded to
Maven Central, which is why a generated project can resolve its dependency without a local install.

---

## 7. Status

| Phase | Scope | Status |
|---|---|---|
| G0 | protocol conformance (8 vectors + the permission/identity wire contracts) | ✅ |
| G1 | runtime core + trust loop, on a hand-written app | ✅ |
| G2 | generator: business request → spec → real Spring Boot source | ✅ |
| G2+ | the generated app runs standalone, and the trust loop holds on the artifact | ✅ |
| G3 | changeability: the change is applied as an additive migration, hand edits are preserved, the new rule is enforced, and **the data already in the database is carried over** | ✅ |

**Not yet in scope (by design):** a Docker image or hosted demo, a user interface, multi-tenancy,
HA, an MCP / OpenAPI bridge for existing systems, and the KeelBase AI pipeline (agent / RAG /
memory) — the latter belongs to a Java AI framework, not to the runtime. Identity is a pluggable
seam rather than a built-out authentication stack, and the datastore is a spike-grade in-memory H2.
