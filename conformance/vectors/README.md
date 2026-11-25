# Frozen contract (vendored snapshot)

This directory is a **read-only snapshot** of the protocol contract — the vectors, the wire schemas
they belong to, and the behaviour-level scenario packs whose `replay` is the neutral-replay corpus.
The machine-readable contract itself lives in the **contract repository**
(`rain6fish/keelbase-contract`), and the protocol half of this snapshot is taken from there; the
scenario packs come from the main repository, which is where they live. The copy mapping:

```
rain6fish/keelbase-contract/                    → this directory
├── canonical-json-v1-vector.json
├── audit-hash-v1-vector.json
├── delegation-token-v1-vector.json
├── risk-level-v1-vector.json
├── governance-binding-v1-vector.json
├── confirmation-lifecycle-v1-vector.json
├── confirmation-lifecycle-v2-vector.json
├── failure-semantics-v1-vector.json
├── wire-schema-registry.json           ← contract id → schema file (v1: two spellings)
├── wire-schema-registry-v2.json        ← the same index written one way — what the Java tests read
└── schemas/                            ← v1 … v5 (+ samples)

KeelBase/Server-NestJS/specs/scenarios/         → scenarios/
├── golden-application-v1.json          ← the packs' `replay` (conformance-profile §2.4)
├── trust-proof-v1.json
├── cross-entry-v1.json
├── security-showcase-v1.json           ← `replay: null` (out of replay scope)
├── failure-path-v1.json                ← `replay: null` (fault injection, not a wire request)
├── demo-intent-v1.json
└── replay.schema.json                  ← the replay grammar (opt-in per pack)
```

**The contract repository is the source of truth** for these files (`rain6fish/keelbase-contract`).
This copy exists so the Java project is self-contained and buildable offline.

## Rules

- **Do not hand-edit.** To refresh, run `scripts/sync-vectors.sh` — it copies every
  `*-vector.json`, every `wire-schema-registry*.json` (the whole registry family, so a future `vN`
  needs no change there) and `schemas/**/*.json` from the contract repository, plus every `*.json`
  under the scenarios directory, and normalises line endings to LF.
- A protocol change is made in the contract repository first (vector → then implementations), never
  here. Scenario packs and the semantic-change checklist still live in the main repo.
- The vector files intentionally contain **no timestamps** — deterministic and diff-able.

**Corresponds to `keelbase-contract` `v1.23.0`** — every vendored file verified byte-identical against
that tag, which is the version CI checks out and compares against (`sync-vectors.sh --check` prints
the current file count, which is why this line does not carry one).

## What this snapshot does not cover

The snapshot vendors the contract's records, not an implementation of every member.
`audit-chain-anchor` (v1.22.0) has no producer and no verifier in this repository: the operation audit
log is not a hash chain, and SM2/SM3 are unavailable in the JDK, so nothing here produces or verifies
a daily anchor. The vendored schema describes the member; it does not assert that this runtime
implements it.

## 本快照不覆盖什么

快照只引入契约的记录，不引入每个成员的实现。`audit-chain-anchor`（v1.22.0）在本仓没有产出方、也没有
校验方：操作审计日志不是哈希链，JDK 内也没有 SM2/SM3，故这里没有任何东西产出或校验每日锚。引入的
schema 描述的是这个成员，并不表示本运行时实现了它。

## The two sources

| Vendored set | Source | Why there |
|---|---|---|
| `protocol/` — vectors, registry, schemas | the **contract repository**, at `v1.23.0` | it is the contract; this repository consumes it, and a version — not `main` — is what a consumer can be pinned to |
| `scenarios/` — scenario packs | the **main repository** (`Server-NestJS/specs/scenarios/`) | scenario packs are not contract content, so they are still taken from where they live |

Whether scenario packs belong in the contract is an open question, and this snapshot does not answer
it by pretending they already do. The drift gate checks both sources, so neither half can rot
unnoticed.

## Moving the pin, and the weekly lag reminder

- **Move it with one command:** `scripts/sync-vectors.sh --bump <tag>`. It rewrites the contract
  `ref:` in `.github/workflows/ci.yml` and `.github/workflows/release.yml`, updates the pin
  statement above, re-copies the snapshot from exactly `<tag>` (exported from the contract clone
  with `git archive`, so the clone is not modified), and finishes by running `--check` — a bump that
  does not verify exits 1. It does not commit, push, create tags, or touch the contract repository.
  Bumping to the tag already pinned re-syncs and re-verifies, and says so.
- **Pins move only at three moments** (CT-4): contract tag cut, consumer release prep, cross-repo
  gate red — in the order contract push → consumer bump → consumer push, same day.
- **The lag reminder only reports:** `bash scripts/check-pin-lag.sh` compares the pin with the
  contract remote's newest tag and reminds you when the pin trails by more than `--lag` released
  tags (default `1`, i.e. one release window is tolerated; two or more is a reminder). It edits
  nothing and is not a push gate. Add `--contract ../keelbase-contract` to read tags from a local
  clone offline, and `--strict` to exit 1 on a reminder. Suggested cadence: weekly, e.g. a Monday
  03:00 cron — `0 3 * * 1 cd /path/to/KeelBase4J && bash scripts/check-pin-lag.sh --strict`.
- **The push order is read, not enforced:** `node scripts/check-push-order.mjs` lines up the three
  CT-4 moments — the contract tag push, this repository's pin bump and the push that carried it —
  and says whether the order and the same-day rule are proven, broken or undecidable. `--online`
  asks GitHub for the tag-push run (exact once `tag-lineage.yml` has run for that tag); offline it
  uses the local fetch bound. `--strict` exits 1 only on a proven violation; undecidable stays 0.
  Run it in the release window right after `check-pin-lag.sh` and keep the output in the release
  record. It writes nothing and is deliberately not a gate: the three moments span two
  repositories, so no single-repository CI can see the order.

**中文**

- **一步移动 pin：** `scripts/sync-vectors.sh --bump <tag>`。它会改写 `.github/workflows/ci.yml` 与
  `.github/workflows/release.yml` 里的契约 `ref:`、更新上面的 pin 陈述、从 `<tag>` 精确重新复制
  快照（用 `git archive` 从契约克隆导出，克隆本身不被修改），最后跑 `--check`——校验不过即退出 1。
  它不提交、不推送、不打 tag、不碰契约仓。bump 到已钉的 tag 也会重新同步并校验，并说明这一点。
- **pin 只在三个时刻移动**（CT-4）：契约打 tag、消费方发布准备、跨仓门禁红——顺序为契约推送 →
  消费方 bump → 消费方推送，同日完成。
- **落后提醒只报告：** `bash scripts/check-pin-lag.sh` 把 pin 与契约远端最新 tag 比较，当 pin 落后
  **超过** `--lag` 个已发布 tag 时提醒（默认 `1`，即容忍一个发版窗口；落后两个及以上才提醒）。
  它不编辑任何文件，也不是推送门禁。加 `--contract ../keelbase-contract` 可离线从本地克隆读 tag，
  加 `--strict` 让提醒时退出 1。建议频率：每周一次，例如周一 03:00 的 cron：
  `0 3 * * 1 cd /path/to/KeelBase4J && bash scripts/check-pin-lag.sh --strict`。
- **推送顺序只读不拦：** `node scripts/check-push-order.mjs` 对齐 CT-4 的三个时刻——契约 tag 推送、
  本仓的 pin bump、以及把这次 bump 带上去的那次推送——并给出顺序与同日规则是「已证成立」「已证违反」
  还是「不可判」。`--online` 向 GitHub 要 tag 推送的运行（该 tag 跑过 `tag-lineage.yml` 后即精确）；
  离线时用本地的 fetch 上界。`--strict` 只在证明违规时退出 1，不可判仍退出 0。发布窗口内紧跟
  `check-pin-lag.sh` 跑一次，把输出留在发布记录里。它不写任何文件，且**有意**不是一道闸：三个时刻分处
  两个仓库，任何单仓 CI 都看不见这个先后。

> **Coverage note**: the snapshot mirrors the contract's own directory, so it can hold a
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
> | `wire-schema-registry-v2.json` + `schemas/` | `PermissionWireTest` — the wire carriers are checked **against the schema**, not against a transcription of it: registry → schema file → `required` / `properties` / `additionalProperties` / `enum`, recursing into nested objects, array items and `$ref` |
> | `scenarios/` | `ScenarioReplayTest` — the second carrier replays the packs' `replay` over HTTP and asserts their `expect` (JV-15 Slice 1). Entries this runtime cannot serve are classified and asserted, never skipped; three of the five packs are in replay scope (`security-showcase` and `failure-path` carry `replay: null` by ruling) |
>
> One `failure-semantics-v1` invariant is reproduced only in part — the runtime has no surface for
> the rest. The specific boundary is listed in `FailureSemanticsTest`'s class comment.
