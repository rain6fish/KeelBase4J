# KeelBase4J

**The Java/Spring carrier for the KeelBase AI Governance Protocol** — so a Java or Spring
application can run AI operations inside the same governance boundary as every other KeelBase
implementation, and prove it by reproducing the same frozen, language-neutral vectors.

<p align="center">
  <a href="https://github.com/rain6fish/KeelBase4J/actions/workflows/ci.yml"><img src="https://github.com/rain6fish/KeelBase4J/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License: Apache-2.0"></a>
  <img src="https://img.shields.io/badge/Java-17-informational" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring_Boot-4.1.1-informational" alt="Spring Boot 4.1.1">
</p>

**The design rule — implement the frozen contract, do not translate the reference.**
The KeelBase protocol is the source of truth, not the TypeScript code that happens to implement it
first. Compatibility is proven by reproducing the language-neutral vectors that live in the main
repository — the same files any other implementation would use.

---

## What this is

KeelBase (the reference implementation is TypeScript, `rain6fish/KeelBase`) defines a
language-neutral AI governance protocol: an audit hash chain, a delegation token, tool risk levels,
a confirmation lifecycle, and the permission/authorization wire contracts.

This repository is the **Java side** of that protocol. It holds two things:

- **a runtime** — a Spring Boot application whose AI operations run only inside the trust loop
  (Identity → Permission → Governance → Confirmation → Audit → Revoke);
- **a generator** — business request → Business Spec → real, standalone, editable Spring Boot source.

KeelBase4J is a **carrier**, not a second product: it exists so the same governance semantics are
available to Java/Spring teams, and so the claim "the protocol is language-neutral" has a second
implementation standing behind it.

---

## 30 seconds

Requires JDK 17+ and Maven.

```bash
mvn test
```

**The suite is green** — `mvn test` covers the protocol library, the runtime, the generator and the
adapter, and the CI badge above is where the live state lives rather than a number written here. Then
watch each claim in the next section actually happen — four scripts, no model required for the first three:

```bash
bash scripts/demo-generated-app.sh    # generate → build → run → walk the trust loop
bash scripts/demo-changeability.sh    # change → regenerate → hand edit survives → new rule enforced
bash scripts/demo-migration.sh        # change → additive migration → existing rows carry over

export DEEPSEEK_API_KEY=...           # the demo module also builds with -Popenai or -Pollama
bash scripts/demo-springai.sh         # a real model on the planner seam
```

Every script exits non-zero if an expected outcome is missing. None of them is a smoke test: each
one is the evidence for a specific claim below.

---

## The trust loop

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

The runtime — not a prompt — enforces each step. Three consequences are worth stating plainly,
because they are where this differs from wrapping a model with instructions:

- **Authentication happens at the request entry, and only there.** Spring Security answers *who is
  this request*; KeelBase answers *may this AI behaviour happen*. There is deliberately no
  `hasRole`, no `@PreAuthorize` and no URL-to-role rule in that configuration — a second answer to
  the same question is a second answer that silently diverges.
- **A delegation token proves a subject and nothing else.** It carries no role, because delegation
  never escalates privilege. The runtime maps the verified subject to a local user and role. The
  adapter that used to read `X-User-Id` / `X-User-Role` off the request is gone — those headers are
  ignored, and a request carrying only them is a 401.
- **A planner proposes; the runtime disposes.** Deciding *what to call* is a replaceable seam
  (`ToolCallPlanner`). Its output is a tool name and its arguments — and nothing else. Risk level,
  confirmation, audit and revoke are read from the tool's own declaration and applied downstream,
  unconditionally. Governance metadata is never sent to the model.

Access to a row is two questions kept apart: the capability gate (*may this action on this subject
happen at all*) and the row range (*which rows* — `own`, `org`, `own_dept`, `own_dept_and_below`,
`custom_dept`, `all`). Rows are ranged by a **typed predicate, never a SQL string**, and missing
facts **tighten** a range rather than widen it.

Design rationale and the full component map: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## What is verified

Everything in this table is checked by a test or a script that ships in this repository. The middle
column is where to look; the last column is the honest boundary of the claim.

| Claim | Evidence | Boundary |
|---|---|---|
| The frozen protocol vectors reproduce | 8 vendored vectors → `CanonicalJsonTest` · `AuditChainTest` · `DelegationTokenTest` · `RiskLevelTest` · `GovernanceBindingTest` · `ConfirmationLifecycleTest` | the snapshot must match the main repo; CI `vector-drift` enforces it |
| Failure dispositions follow the vector | `FailureSemanticsTest` | — |
| A caller cannot state who it is or what it may do | `AuthenticationTest` | identity is a thin SPI; the default adapter maps a subject to declared local facts |
| Capability decision and row range stay separate | `PermissionWireTest` · `AuthorizationMappingTest` · `ScopeFilterTest` | rules are declared in code (delivery tier A) |
| A write is not executed until a human approves | `TrustLoopTest` | — |
| One confirmation token executes the tool exactly once | `ConfirmationConcurrencyTest` (written red first) · the eight-at-once check in `demo-generated-app.sh` | — |
| The audit chain is tamper-evident and appended under a row lock | `AuditChainConcurrencyTest` · `GET /audit/verify` | the spike's datastore is in-memory, so it is per-process |
| The generated application runs standalone | `demo-generated-app.sh` | the generator handles the CRM-shaped request it was built for |
| A change carries the data already in the database | `demo-migration.sh` | Flyway owns the schema; `ddl-auto=validate` |
| Regeneration merges the developer's edits | `demo-changeability.sh` | a **line-level** merge: it merges text, not meaning |
| A real model routes, and the runtime still holds the write | `demo-springai.sh` (DeepSeek, real key) | needs a key; it is a human-run demo, not a CI gate |
| The frontend's own modules drive this runtime | `demo-golden-path.sh` | the console's API modules against a live instance; the browser UI itself is the main repo's |

Every claim above is measured on this checkout — `mvn test` plus the no-key demos, each of which
prints its own checks as it runs.

---

## What this is not

Read this section before quoting anything above.

1. **Not a product.** There is no Docker image, no hosted demo and no user interface of its own.
   The KeelBase frontends live in the main repository and their own API modules run against this
   runtime (`scripts/demo-golden-path.sh`), but nothing here is packaged for a browser.
2. **Not a finished generator.** `BusinessSpecParser` is a deterministic router over the request
   shapes it recognises — the CRM-shaped request, and the incremental changes that edit its spec. It does not turn
   arbitrary natural language into modules, and it does not call a model.
3. **Not an agent framework.** No RAG, no embeddings, no memory, no sub-agents, no proactive AI.
   These are explicit non-goals (ADR-0004); unfreezing any of them requires a new ADR.
4. **Not a bridge for existing systems.** No MCP or OpenAPI ingestion here. The Java-side bridge
   that adds governance to an existing system is a separate repository
   (`rain6fish/KeelBase-java-starter`).
5. **Not a replacement for the main repository.** This repository only *consumes* the protocol.
   Protocol changes land in the contract repository first (vectors → implementation); a divergence is
   caught by CI, not tolerated.
6. **Not production-tier data or identity.** The runtime's datastore is in-memory H2, its
   authorization rules are declared in code, and there is no IdP, no `roles`/`permissions` table and
   no multi-tenancy. A real directory or IdP is an adapter behind the same seam — not built here.
7. **Its positioning is not decided here.** Whether and how this line becomes a product is settled
   in internal decision records, not in this repository. What this repository holds is the Java
   carrier and the evidence that it reproduces the protocol.

---

## Modules

Five Maven modules with a **one-way** dependency edge: `runtime` and `generator` depend on
`protocol`, never the reverse; nothing depends on the runtime.

| Module | What | Depends on |
|---|---|---|
| `keelbase4j-protocol` | The frozen protocol — canonical JSON, audit hash chain, delegation token, risk levels, governance binding, confirmation lifecycle, and the permission/authorization wire contracts | **nothing** (JDK only) |
| `keelbase4j-runtime` | The governed runtime — AI operations run inside the trust loop | `protocol`, Spring Boot |
| `keelbase4j-generator` | Business request → Business Spec → standalone Spring Boot source | `protocol` |
| `keelbase4j-springai` | Adapter — implements the runtime's `ToolCallPlanner` seam with Spring AI; inert unless a model is configured | `runtime` |
| `keelbase4j-demo` | Runnable deployment — runtime + adapter + **one** provider, chosen by Maven profile (`deepseek` default, `openai`, `ollama`) | `runtime`, `springai` |

`keelbase4j-protocol` is the artifact a **generated application depends on**, which is why it is
kept free of third-party dependencies: while it shared one artifact with the runtime, that library
silently carried Spring Security, and a generated app inheriting its auto-configuration locked down
every endpoint. An adapter — a model provider, an identity provider — belongs **outside** the
runtime and depends on it.

The runtime serves fourteen routes, all mounted under the reference's `/api/v1` prefix
(`server.servlet.context-path`), so a runtime-neutral frontend keeps one base URL and no
per-runtime branch:

| Method | Path | Purpose |
|---|---|---|
| POST | `/ai/chat` | planner → governed tool call |
| POST | `/ai/chat/stream` · `/admin/ai/chat/stream` | the same turn over SSE; the `/admin` path requires the admin role |
| POST | `/ai/confirmations/{token}` | `approve` (executes) or `decline` (writes nothing) |
| GET | `/ai/tool-effects` | recorded side effects |
| DELETE | `/ai/tool-effects/{id}` | revoke → local compensation (soft delete) |
| GET | `/audit/verify` | recompute and verify the audit hash chain |
| GET | `/auth/me` | who the caller is, as this deployment knows them |
| GET | `/auth/me/permissions` | the caller's capability list, in the frozen contract shape |
| GET | `/auth/oauth/providers` | the federated providers offered here — none, and that is the answer |
| POST | `/auth/login-stats` | the login page's visit ping, answered `ok: false` on purpose: there is no sink to record it |
| GET | `/customers` | row-scoped by the caller's range |
| GET | `/app/capabilities` · `/app/provenance` | what this deployment declares about itself |

Component-by-component detail: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## The generator

```text
Business description
   → BusinessSpecParser → BusinessSpec        (entities, fields, ownership rule, AI tools + risk)
   → JavaGenerator      → Spring Boot project  (real .java sources + pom.xml + README)
```

The output is **normal, editable source** — a Maven project that builds on its own, with its own
governance wiring, depending only on the frozen protocol *library* and never on a KeelBase4J
runtime service. A generated application carries the identity seam, the contract-derived
authorization (`GET /auth/me/permissions`, same path and shape as the runtime), Flyway-owned
schema, and the confirmation store — so it makes the same governance decisions the protocol
defines, without this repository in the loop.

Regeneration is a **three-way merge** against what the generator produced last time
(`.keelbase/baseline/`), so a hand edit survives anywhere in a file; a file the generator never
produced is left untouched and reported, and a genuine conflict is reported rather than guessed at.

```bash
bash scripts/demo-generated-app.sh    # generate → build → run → trust loop, on the artifact
bash scripts/demo-migration.sh        # the change carries existing rows
bash scripts/demo-changeability.sh    # the hand edit survives the change
```

See [What this is not](#what-this-is-not) items 2 and 3 for the boundary of this section.

---

## Portability: the second carrier

This repository is the first proof that a KeelBase implementation can be written in another
language and still be the *same* protocol, rather than a similar-looking one:

- **8 frozen vectors** are vendored under `conformance/vectors/` and reproduced assertion by
  assertion — canonical JSON (byte-faithful `JSON.stringify` sorted-key semantics, including
  UTF-16 key order and JavaScript number formatting), the audit hash chain, the delegation token,
  risk levels, governance binding, the confirmation lifecycle, and failure semantics.
- **The wire contracts are reproduced, not reinvented.** `permission-decision`,
  `permission-capability-list`, `org-membership-scope` and `authorization` are carried in
  `cn.com.keelbase.protocol` and served in the frozen shape.
- **The vectors are a read-only snapshot.** CI's `vector-drift` job diffs them against the copy they
  are refreshed from on every push, so the snapshot cannot silently drift. They are never hand-edited
  here; the contract repository is authoritative for what they contain.

---

## Protocol sources

The authoritative protocol lives in its own repository —
[`rain6fish/keelbase-contract`](https://github.com/rain6fish/keelbase-contract): the language-neutral
vectors, the wire-object schemas, and the single version line they share. Both runtimes consume it;
neither owns it.

- the contract repository — the vectors and schemas that decide conformance;
- `docs/protocols/ai-governance-protocol.md` in the main repository — the protocol in prose
  (§2 chain · §3 token · §4 risk levels);
- §5.1 — how an implementation self-certifies against those vectors.

The copy under `conformance/vectors/` is a snapshot; see
[conformance/vectors/README.md](conformance/vectors/README.md) for how it is refreshed.

## Repositories

- [`keelbase-contract`](https://github.com/rain6fish/keelbase-contract) — the protocol, independent of any implementation
- [`KeelBase`](https://github.com/rain6fish/KeelBase) — the TypeScript runtime and the product documentation
- `KeelBase4J` — this repository; the second carrier of the same protocol
- [`KeelBase-java-starter`](https://github.com/rain6fish/KeelBase-java-starter) — Spring Boot starter for the Java side of the bridge

## Compatibility

The protocol has its own version line, independent of any runtime's. This table says which **contract
version a runtime answers for** — which objects it has committed to speaking.

| Runtime | Runtime version | Contract version |
|---|---|---|
| [`KeelBase`](https://github.com/rain6fish/KeelBase) (TypeScript) | `v1.0.11` | **v1.0.1** — carried in-tree; that release predates the contract repository |
| [`KeelBase`](https://github.com/rain6fish/KeelBase) (TypeScript) | `main`, **unreleased** | **v1.2.0** — bound as a submodule |
| `KeelBase4J` (Java) | `v0.1.0` | **v1.1.0** — vendored snapshot, taken from the contract repository |

The snapshot under `conformance/vectors/` has two sources, and CI checks both: the **protocol half**
comes straight from the contract repository — at the version this repository answers for, not through a
repository that consumes it — and the **scenario packs** come from the main repository, which is where
they live. The chain that once made this a **copy of a copy** is therefore gone for the protocol: the
drift gate compares the snapshot against the contract itself, so the main repository falling behind the
contract can no longer hide here either.

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — subsystems, component versions, the trust loop, and
  the authorization/identity decisions
- [CLAUDE.md](CLAUDE.md) — the frozen rules this repository is built to (dependency direction, the
  protocol as source of truth, generated output being real source)
- [conformance/vectors/README.md](conformance/vectors/README.md) — the vendored snapshot

## License

Apache-2.0.
