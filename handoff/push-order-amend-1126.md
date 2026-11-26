# Push-order reader: the hook's amend timestamp was read as the real one — 推送顺序读数器把钩子的 amend 时刻当成真实时间

Worktree `elastic-greider-94525c`, branch `claude/blissful-brahmagupta-73d58e` (base local `main` `e870a49`).
Only `scripts/check-push-order.mjs` and its `--self-test` changed. Exit codes 0/1/2, the CI contract,
other scripts, `conformance/vectors/**` and the workflows are untouched.

## English

### Root cause

`realBumpTime()` took the oldest `HEAD` reflog entry whose sha equalled the bump commit and labelled it
`exact`. Its comment assumed that entry sat before the `commit (amend)` line the post-commit hook
writes. That holds only for unamended commits: the hook amends every commit, so the pre-amend sha is
gone and the only matching entry is the amend, whose timestamp is the mapped (rewritten) one. The
script then compared a rewritten time against the tag push and reported the wrong direction. The
`parseRawReflog` fallback (`entries.find(entry => entry.newSha === bump.sha)`) had the same defect.

### Fix

- `bumpMomentFromReflog()` finds the oldest entry for the sha. If it is a `commit (amend)`, it follows
  the amend's `oldSha` chain backwards until it reaches a commit operation and takes that entry's
  time. A missing pre-image, a checkout instead of a commit, or a chain that never ends in a commit
  returns `null` instead of any `exact` value.
- `fallbackBumpMoment()` keeps the old loose reading (oldest entry that moved HEAD to the sha) for
  commits this clone never logged as a commit, but never takes an amend entry.
- `realBumpTime()` reads the worktree's own HEAD log and the common git dir's HEAD log, prefers a
  proven creation from either before the loose reading, and only then falls back to the commit date
  marked `approx`.

### Red / green

End-to-end on a throwaway clone with a real hook-style amend (commit `13:33:06`, amend stamped
`23:53:06`):

- old script: `consumer pin bump 2026-11-23T23:53:06+08:00 (exact)` — wrong.
- new script: `consumer pin bump 2026-11-23T13:33:06+08:00 (exact)` — right.

`--self-test`: 9/9 judge cases and 10/10 reflog cases pass. The reflog fixtures cover unamended,
single amend, double amend, missing pre-image, checkout-only hits (proven reader refuses, loose
fallback keeps the old reading), an amend whose pre-image arrived by checkout, and no reflog at all.

### Real reading (read-only)

`node scripts/check-push-order.mjs --consumer D:/Rain6fish/KeelBase --tag v1.23.0 --bump-commit 2ea7f597`
(`--tag` is needed because `KeelBase` now consumes the contract as a gitlink, so `readPin()` finds no
`ref:` line; the absolute `--consumer` path is needed because this worktree has no `../KeelBase`):

- before: bump `2026-11-23T23:53:06 (exact)` and a false finding "the consumer was pushed … before its
  pin bump"; the real violation was not named.
- after: bump `2026-11-23T13:33:06 (exact)`; the false finding is gone. With `--online` the tag-push run
  is `2026-11-24T19:03:24`, so the remaining VIOLATION is the true one: the contract tag was pushed
  after the pin bump, and not on the same day.

### Same-class defect fixed in place

On this worktree, `e870a49` is known to its own HEAD log only through the `checkout` written when the
worktree was created (`2026-11-26T12:49:44`); the commit itself (`19:42:53`) is in the common log. The
old reader (and the first version of the fix) labelled that checkout `exact` and so reported "consumer
pushed before its bump". `realBumpTime()` now prefers the proven commit entry from either log; the same
command now reads `2026-11-25T19:42:53 (exact)` and returns UNDECIDED (the tag push is unbounded
offline) instead of a false violation.

### Not done / risks

- `handoff/w3-advance-1124.md` §7.1 and the runbook were not edited; §7.1 is a historical record now
  answered here.
- Not verified: a commit created by `git cherry-pick` or `rebase` whose only creation entry is not a
  `commit` operation now reads `approx` instead of a possibly wrong `exact`. That is the intended
  direction (honest bound), but it is a behaviour change for such commits.
- The full `scripts/release-gate.sh` was not run (script-only change); the touched gates were run
  individually.

## 中文

### 根因

`realBumpTime()` 取 `HEAD` reflog 里 sha 等于 bump 提交的最早一条并标 `exact`，注释假设它位于
post-commit 钩子写下的 `commit (amend)` 之前。这只对未 amend 过的提交成立：钩子会给每笔提交 amend
一次，amend 前的 sha 已不存在，唯一能匹配的是 amend 那条，其时间戳是被映射（改写）后的值。脚本于是
拿改写后的时间去比契约 tag 推送，报出了错误的方向。`parseRawReflog` 的回退
（`entries.find(entry => entry.newSha === bump.sha)`）有同样的缺陷。

### 改法

- `bumpMomentFromReflog()` 找到该 sha 最早的条目。若是 `commit (amend)`，沿 amend 的 `oldSha` 链往回
  走到一条提交操作，取那条的时间。前像缺失、命中的是 checkout 而非提交、或链始终没落到提交条目，一律
  返回 `null`，不给任何 `exact` 值。
- `fallbackBumpMoment()` 保留旧的宽松口径（最早把 HEAD 挪到该 sha 的条目），用于本克隆从未以提交操作
  记录过其产生的提交；但绝不取 amend 条目。
- `realBumpTime()` 读 worktree 自己的 HEAD 日志与公共 git 目录的 HEAD 日志，先在两者里找可证明的创建
  时刻，再退到宽松口径，最后才用提交日期并标 `approx`。

### 红绿

在一个临时克隆上用真实的钩子式 amend（提交 `13:33:06`，amend 改写为 `23:53:06`）端到端对照：

- 旧脚本：`consumer pin bump 2026-11-23T23:53:06+08:00 (exact)`——错。
- 新脚本：`consumer pin bump 2026-11-23T13:33:06+08:00 (exact)`——对。

`--self-test`：判决 9/9、reflog 读数 10/10 全过。reflog 夹具覆盖未 amend、单次 amend、两次 amend、
前像缺失、只有 checkout（可证明读数拒绝、宽松口径保留旧读数）、前像由 checkout 带入的 amend、以及
完全没有 reflog。

### 真实读数（只读）

`node scripts/check-push-order.mjs --consumer D:/Rain6fish/KeelBase --tag v1.23.0 --bump-commit 2ea7f597`
（需要 `--tag`：`KeelBase` 现在以 gitlink 消费契约，`readPin()` 找不到 `ref:` 行；需要绝对 `--consumer`
路径：本 worktree 没有 `../KeelBase`）：

- 改前：bump 读作 `2026-11-23T23:53:06 (exact)`，并报出「消费方推送早于 bump」的假结论，真正的违规
  没有被说出来。
- 改后：bump 读作 `2026-11-23T13:33:06 (exact)`，假结论消失。带 `--online` 时 tag 推送为
  `2026-11-24T19:03:24`，剩下的 VIOLATION 就是真实那条：契约 tag 推送晚于 pin bump，且不在同一日。

### 一并修掉的同类缺陷

本 worktree 里 `e870a49` 在它自己的 HEAD 日志中只有创建 worktree 时写下的 `checkout`
（`2026-11-26T12:49:44`），提交本身（`19:42:53`）在公共日志。旧读数器（以及本修复的第一版）把这条
checkout 标成 `exact`，于是报出「消费方推送早于 bump」。`realBumpTime()` 现在优先采用任一份日志里可
证明的提交条目；同一条命令现在读作 `2026-11-25T19:42:53 (exact)`，结论是 UNDECIDED（离线时 tag 推送
无界），不再是假违规。

### 未做 / 风险

- 没有改 `handoff/w3-advance-1124.md` §7.1 与 runbook；§7.1 是历史记录，其问题在这里得到回答。
- 未验证：由 `git cherry-pick` 或 `rebase` 产生、且唯一创建条目不是提交操作的 bump，现在读作
  `approx` 而非可能错误的 `exact`。这是有意的方向（诚实的边界），但对该类提交是行为变化。
- 没跑完整的 `scripts/release-gate.sh`（本次只改脚本）；涉及的闸已单独跑过。
