# Snapshots that are not the protocol contract

`vectors/` holds the frozen contract snapshot and documents itself. This directory holds one more
vendored file, and it is deliberately kept **outside** `vectors/` — that directory's rule is "refresh
only via `scripts/sync-vectors.sh`", and that script knows nothing about this file. Putting it there
would have made the rule untrue.

| File | Mirrored from | Refreshed by |
|---|---|---|
| `coverage-gaps.json` | `KeelBase/Server-NestJS/src/ai/audit/coverage-gaps.ts` — the registry's single source | `node scripts/check-coverage-gaps-sync.mjs --sync`, run **in the main repository** |

**This is a snapshot, not a source.** The coverage-gap registry is minted in the main repository's
TypeScript module, because a gap id has to mean the same thing whichever runtime emitted a package.
This runtime cannot read TypeScript, so it keeps a copy — and the main repository's
`npm run check:coverage-gaps` compares the two and fails on drift (content-wise, not byte-wise: line
endings and indentation are not drift, a changed statement or predicate is).

**Do not hand-edit it.** Edit the module and refresh.

**`coverage-gaps.json` is mirror only.** It is a mirrored snapshot for third parties and the drift
check; no code in this repository consumes it.

---

# 不属于协议契约的快照

`vectors/` 装的是冻结的契约快照，它自己有一份说明。本目录再装一个 vendored 文件，且**刻意放在
`vectors/` 之外** —— 那个目录的规矩是「刷新一律跑 `scripts/sync-vectors.sh`」，而那个脚本对本文件
一无所知。放进那里会让那条规矩变成一句不成立的话。

| 文件 | 镜像自 | 刷新方式 |
|---|---|---|
| `coverage-gaps.json` | `KeelBase/Server-NestJS/src/ai/audit/coverage-gaps.ts` —— 登记表的单一真源 | 在**主仓**跑 `node scripts/check-coverage-gaps-sync.mjs --sync` |

**这是快照，不是真源。** 缺口登记表在主仓的 TypeScript 模块里铸造 —— 因为同一个缺口 id 无论由哪个
运行时发出，含义必须相同。本运行时读不了 TypeScript，故存一份副本；而主仓的
`npm run check:coverage-gaps` 比对两边、漂移即失败（**比内容、不比字节**：行尾与缩进不算漂移，改过的
陈述或谓词算）。

**禁止手改。** 改模块，然后刷新。

**`coverage-gaps.json` 只是镜像。** 它是一份供第三方与漂移检查使用的镜像快照；本仓没有任何代码消费它。
