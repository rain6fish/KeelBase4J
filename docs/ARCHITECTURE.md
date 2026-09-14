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
| Conformance evidence | this repo `mvn test` (42 assertions) | proves cross-runtime parity (CE-1 role ③) |

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

### 3.2 `cn.com.keelbase.runtime` — the runtime core (G1 ✅)

The hand-written reference runtime: a Spring Boot app whose AI operations run only inside the trust
boundary.

| Package | Responsibility |
|---|---|
| `domain` | `Customer` / `FollowUp` entities + repositories (own-scope, soft-deletable) |
| `identity` | `Principal`, `IdentityResolver` (pluggable identity seam) |
| `authz` | `OwnershipGuard` — row-level permission (own vs manager) |
| `tool` | `AiTool` contract, `ToolRegistry`, the two AI tools (R1 read / R3 write) |
| `governance` | `GovernanceService` (risk → gate), confirmation store + entity |
| `effect` | `SideEffect` + record/revoke (content-derived idempotency, class-aware revoke) |
| `audit` | `AuditService` — hash-chained AI audit + verify |
| `engine` | `GovernedExecutionEngine` — the loop: gate → confirm → execute → audit → effect |
| `web` | REST: `/ai/chat`, confirmations, tool-effects, `/audit/verify` |

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
└── src/main/
    ├── java/com/example/<module>/
    │   ├── Application.java
    │   ├── domain/   <Entity>.java · <Entity>Repository.java
    │   ├── ai/       AiTool · GovernanceGate · ToolRegistry · GovernanceEngine
    │   │             ConfirmationStore · SideEffectStore · AuditChainStore · <Tool>Tool
    │   └── web/      AiController · GovernanceController
    └── resources/application.properties
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
  hard dependency). In this repo that is the `IdentityResolver` seam — the spike resolves the
  principal from request headers; a real deployment swaps in a session / delegation token without
  touching the rest of the runtime.
- **No new "identity contract".** Authorization semantics map onto the wire contracts already frozen
  in the KeelBase main repo (`authorization`, `permission-decision`, `permission-capability-list`,
  `org-member-item`, `org-membership-scope`, `delegation-token-claims`). Java adds only a thin SPI.
- **Spike scope:** no Keycloak, no unified permission console (parked). Row-level permission is
  enforced by `OwnershipGuard` (own vs manager) and, in generated apps, by per-entity policy rules.

## 6. Build & verification

```bash
mvn test                              # G0 conformance (42) + G1 trust loop (1) + G2 generator (2)
mvn -DskipTests install               # install the protocol library into the local repo
bash scripts/demo-generated-app.sh    # generate → build → run → exercise the generated app
```

CI (`.github/workflows/ci.yml`): `conformance` (JDK 17, `mvn verify`) + `vector-drift`
(diff the vendored vectors against the main repo).

---

## 7. Status

| Phase | Scope | Status |
|---|---|---|
| G0 | protocol conformance (5 vectors, 42 assertions) | ✅ |
| G1 | runtime core + trust loop (S3/S4 on a hand-written app) | ✅ |
| G2 | generator: NL → spec → real Spring Boot source (S1/S2) | ✅ |
| G2+ | generated app runs standalone; trust loop holds on the artifact (S3 axes A+B, S4) | ✅ |
| G3 | changeability (S5 — the spike's kill gate): change applied, hand edits preserved, rule enforced | ✅ |

**Not yet in scope (by design):** authentication stack (identity is a pluggable seam), multi-tenancy,
HA, UI, and the KeelBase AI pipeline (agent / RAG / memory) — the latter belongs to a Java AI
framework, not to the runtime.
