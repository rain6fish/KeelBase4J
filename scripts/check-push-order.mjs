#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// Read-only evidence for the CT-4 push order: contract push -> consumer pin bump -> consumer push,
// same day. This script changes no file, starts nothing, and is not a gate. It prints the three
// moments it can prove and says which of them it can only bound.
//
// Where the moments come from, and why the obvious sources are not all usable:
//
//   * Contract tag creation — `%(creatordate)` of the tag ref (the tagger's clock for an annotated
//     tag). This is a LOWER bound on the push, never the push itself.
//   * Contract tag push — GitHub Actions creates a run for a tag push, and its `createdAt` is within
//     seconds of the push. Locally, a clone's reflog records a fetch that brought the tag; that time
//     is an UPPER bound (the tag was already on the remote by then). Nothing else on the local side
//     records a tag push: tags have no reflog, and `ls-remote` has no time.
//   * Consumer pin bump — the commit that moved the pin. The commit date is NOT usable as a real
//     clock: the post-commit hook in these repositories maps weekday 05:00-19:00 commit times into
//     19:00-03:00 (author and committer both). It does that by amending, so the `HEAD` reflog entry
//     that carries the bump's final sha is the `commit (amend)` line and its timestamp is the
//     rewritten one. The real time sits on the entry that created the pre-amend commit: this script
//     follows the amend's sha chain back to it, and falls back to the commit date marked `approx`
//     when that entry cannot be proven.
//   * Consumer push — the remote-tracking reflog (`update by push`) records the push itself. If the
//     bump commit is not an ancestor of the tracked remote branch, it was never pushed.
//
// The verdict has three values. OK means the order and the same-day rule are both provable.
// VIOLATION means one of them is provably broken. UNDECIDED means the evidence is a bound that
// straddles the comparison — that is not a failure, and it is not a pass either. CI cannot run this
// as a gate: the three moments live in two repositories, so the ordering is invisible from inside
// either one. It belongs in the release runbook as a reading taken before the push, alongside
// `check-pin-lag.sh`.
//
//   node scripts/check-push-order.mjs                     # read the pin and local evidence
//   node scripts/check-push-order.mjs --online            # also ask GitHub for tag/consumer runs
//   node scripts/check-push-order.mjs --contract ../keelbase-contract --tag v1.22.0
//   node scripts/check-push-order.mjs --bump-commit <sha> # any consumer, including one whose pin is a submodule
//   node scripts/check-push-order.mjs --strict            # exit 1 only on a proven violation
//   node scripts/check-push-order.mjs --json              # machine-readable
//   node scripts/check-push-order.mjs --self-test         # judge the built-in fixtures and the reflog reader
//
// Exit codes: 0 report-only (whatever it found) or an OK/UNDECIDED verdict under --strict;
// 1 --strict and a proven violation; 2 usage or environment (no pin, no tag, not a clone).
//
// ── 中文 ──
// CT-4 推送顺序的只读证据：契约推送 → 消费方 pin bump → 消费方推送，同日完成。本脚本不改任何文件、
// 不起任何东西，也不是一道闸。它打印能证明的三个时刻，并说明其中哪个只能给出边界。
//
// 三个时刻的来源，以及为什么「看起来最直接」的来源并不都能用：
//
//   * 契约 tag 创建 —— tag ref 的 `%(creatordate)`（附注 tag 即打 tag 那台机器的时钟）。这是推送的
//     **下界**，永远不是推送本身。
//   * 契约 tag 推送 —— GitHub Actions 会为 tag 推送创建一次运行，其 `createdAt` 与推送相差数秒。
//     本地侧，克隆的 reflog 会记录一次把该 tag 取回来的 fetch；那个时刻是**上界**（tag 那时已经在
//     远端了）。本地没有任何别的东西记录 tag 推送：tag 没有 reflog，`ls-remote` 没有时间。
//   * 消费方 pin bump —— 移动 pin 的那笔提交。提交日期**不能**当作真实时钟：这些仓库的 post-commit
//     钩子把工作日 05:00–19:00 的提交时间映射到 19:00–次日 03:00（作者与提交者同时改写）。钩子靠
//     amend 做到这一点，于是带着 bump 最终 sha 的 `HEAD` reflog 条目就是 `commit (amend)` 那条，
//     它的时间是被改写的那个。真实时刻落在创建 amend 前那笔提交的条目上：本脚本沿 amend 的 sha 链
//     回溯到它；证不出该条目时退回提交日期并标 `approx`。
//   * 消费方推送 —— 远端跟踪 reflog 的 `update by push` 记录的就是推送本身。若 bump 提交不是被跟踪
//     远端分支的祖先，说明它从未被推送。
//
// 判决有三种。OK 表示顺序与同日规则都可证明。VIOLATION 表示其中一条**被证明**违反了。UNDECIDED 表示
// 证据是一条跨过比较点的边界——那不是失败，也不是通过。CI 不能把它做成闸：三个时刻分处两个仓库，
// 从任一仓内部都看不见这个先后。它的位置是发布 runbook 里、推送之前与 `check-pin-lag.sh` 并列的一次读数。
//
//   node scripts/check-push-order.mjs                     # 读 pin 与本地证据
//   node scripts/check-push-order.mjs --online            # 再问 GitHub 要 tag/消费方 的运行
//   node scripts/check-push-order.mjs --contract ../keelbase-contract --tag v1.22.0
//   node scripts/check-push-order.mjs --bump-commit <sha> # 任意消费方，含 pin 是 submodule 的那种
//   node scripts/check-push-order.mjs --strict            # 只有在**证明**违规时才退出 1
//   node scripts/check-push-order.mjs --json              # 机器可读
//   node scripts/check-push-order.mjs --self-test         # 用内置夹具跑判决与 reflog 读数器
//
// 退出码：0 只报告（不论发现什么），或 `--strict` 下 OK/UNDECIDED；1 `--strict` 且证明违规；
// 2 用法或环境问题（读不到 pin、没有 tag、不是克隆）。
'use strict';

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..');
const DAY_SECONDS = 86400;
// CT-4's "same day" is the operator's +0800 day.
// CT-4 的「同日」按操作者的 +0800 日历日。
const LOCAL_OFFSET_SECONDS = 8 * 3600;

function usage() {
  const text = fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split(/\r?\n/);
  const start = text.findIndex((l) => /^\/\//.test(l));
  for (let i = start; i < text.length && /^\/\//.test(text[i]); i += 1) {
    if (/^\/\/ ── 中文 ──/.test(text[i])) break;
    if (i === start) continue;
    process.stdout.write(`${text[i].replace(/^\/\/ ?/, '')}\n`);
  }
}

function git(dir, args) {
  return execFileSync('git', ['-C', dir, ...args], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}

function gitOrNull(dir, args) {
  try {
    return git(dir, args);
  } catch {
    return null;
  }
}

function isGitClone(dir) {
  return Boolean(gitOrNull(dir, ['rev-parse', '--git-dir']));
}

function epochOf(iso) {
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) throw new Error(`not a timestamp: ${iso}`);
  return Math.floor(ms / 1000);
}

function localDay(epoch) {
  return Math.floor((epoch + LOCAL_OFFSET_SECONDS) / DAY_SECONDS);
}

function formatLocal(epoch) {
  const d = new Date((epoch + LOCAL_OFFSET_SECONDS) * 1000);
  return `${d.toISOString().replace(/\.\d{3}Z$/, '')}+08:00`;
}

function readPin(consumerDir) {
  const files = ['.github/workflows/ci.yml', '.github/workflows/release.yml'];
  for (const rel of files) {
    const file = path.join(consumerDir, rel);
    if (!fs.existsSync(file)) continue;
    const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
    let inContractBlock = false;
    for (const line of lines) {
      if (/^\s*repository:\s*rain6fish\/keelbase-contract\s*$/.test(line)) {
        inContractBlock = true;
        continue;
      }
      if (inContractBlock && /^\s*ref:\s*/.test(line)) {
        return line.replace(/^\s*ref:\s*/, '').trim();
      }
    }
  }
  return '';
}

function findBumpCommit(consumerDir, pin) {
  const out = gitOrNull(consumerDir, [
    'log',
    '--format=%H%x09%cI%x09%s',
    '-S',
    `ref: ${pin}`,
    '--',
    '.github/workflows/ci.yml',
    '.github/workflows/release.yml',
  ]);
  if (!out) return null;
  // A later pin move *removes* the old string, and `-S` reports that commit too. Keep only a commit
  // whose tree actually carries the pin, so the removal that retired it is not mistaken for it.
  // 下一次抬 pin 会**删掉**旧字符串，`-S` 也会报出那笔提交。只保留树里确实带着该 pin 的提交，
  // 免得「撤下它的那笔」被当成「设上它的那笔」。
  for (const line of out.split(/\r?\n/).filter(Boolean)) {
    const [sha, commitDate, subject] = line.split('\t');
    try {
      git(consumerDir, [
        'grep',
        '-q',
        '-e',
        `ref: ${pin}`,
        sha,
        '--',
        '.github/workflows/ci.yml',
        '.github/workflows/release.yml',
      ]);
      return { sha, commitDate, subject };
    } catch {
      /* this commit removed the pin rather than setting it — keep looking older */
      // 这笔是撤下 pin 而不是设上 pin——继续往更早找。
    }
  }
  return null;
}

// The file is in `.git/logs/...` even when the clone is a linked worktree, where `.git` is a file.
// 即便克隆是链接 worktree（`.git` 是个文件），日志仍在 `.git/logs/...` 下，故走 `--git-path` 取。
function reflogPath(dir, ref) {
  const rel = gitOrNull(dir, ['rev-parse', '--git-path', `logs/${ref}`]);
  return rel ? path.resolve(dir, rel) : null;
}

function parseRawReflog(file) {
  if (!fs.existsSync(file)) return [];
  const entries = [];
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^([0-9a-f]{40}) ([0-9a-f]{40}) .*? (\d{9,}) ([+-]\d{4})\t(.*)$/);
    if (!m) continue;
    entries.push({ oldSha: m[1], newSha: m[2], epoch: Number(m[3]), message: m[5] });
  }
  return entries;
}

function isAncestor(dir, ancestor, descendant) {
  try {
    git(dir, ['merge-base', '--is-ancestor', ancestor, descendant]);
    return true;
  } catch {
    return false;
  }
}

// The reflog is chronological and `main` normally only moves forward, so the first entry that
// contains the commit is the push that carried it. Binary search keeps this to a handful of
// `merge-base` calls; a rewind that breaks monotonicity falls back to a linear scan.
// reflog 按时间排列，而 `main` 正常只前进，故「第一条包含该提交的记录」就是带它上去的那次推送。
// 二分把 `merge-base` 调用压到个位数；若一次回退破坏了单调性，退回线性扫描。
function firstPushCarrying(dir, ref, sha) {
  const entries = parseRawReflog(reflogPath(dir, ref));
  if (!entries.length) return null;
  const contains = (i) => isAncestor(dir, sha, entries[i].newSha);

  let lo = 0;
  let hi = entries.length - 1;
  let found = -1;
  if (contains(hi)) {
    while (lo <= hi) {
      const mid = Math.floor((lo + hi) / 2);
      if (contains(mid)) {
        found = mid;
        hi = mid - 1;
      } else {
        lo = mid + 1;
      }
    }
    // Not monotone: fall through to the linear scan.
    // 非单调：退回线性扫描。
    if (found > 0 && contains(found - 1)) found = -1;
  }
  if (found === -1) {
    found = entries.findIndex((_, i) => contains(i));
    if (found === -1) return null;
  }
  return { epoch: entries[found].epoch, tip: entries[found].newSha, message: entries[found].message };
}

// The post-commit hook amends every commit and stamps the rewrite with the mapped (rewritten)
// committer date, so the HEAD reflog entry that carries the bump's final sha is the amend, and its
// timestamp is not the real commit time. The real time is on the entry that created the pre-amend
// commit: follow the amend's sha chain backwards until the entry is not an amend. Anything that
// cannot be proven — a missing pre-image, a checkout instead of a commit, a chain that never
// reaches a commit entry — returns null, so the caller can look in its other log, then fall back.
// post-commit 钩子会把每笔提交 amend 一次，并给改写配上映射后的（被改写的）提交者时间，于是带着 bump
// 最终 sha 的 HEAD reflog 条目是 amend 那条，它的时间不是真实提交时刻。真实时刻在创建 amend 前那笔提交
// 的条目上：沿 amend 的 sha 链往回走，直到条目不再是 amend。证不出来的情况——前像不在日志里、命中的是
// checkout 而非提交、或链始终没落到一条提交条目——返回 null，由调用方去查另一份日志，再退回。
function bumpMomentFromReflog(entries, sha) {
  const isAmend = (entry) => /^commit \(amend\)/.test(entry.message);
  // Entries are chronological (oldest first), so the first match is the oldest operation that put
  // this sha on HEAD — the commit itself, or, when the hook amended it, the amend.
  // 条目按时间排列（旧在前），故第一条匹配就是把这个 sha 放上 HEAD 的最早操作——提交本身；被钩子
  // amend 过时，就是 amend 那条。
  let index = entries.findIndex((entry) => entry.newSha === sha);
  if (index === -1) return null;
  if (!isAmend(entries[index])) {
    // A commit operation records the real committer date; a checkout or reset that merely moved HEAD
    // here records a later moment, so it proves nothing about the creation. Another log may hold the
    // commit itself — the caller looks there before falling back.
    // 提交操作记的是真实提交者时间；只是把 HEAD 挪到这里的 checkout / reset 记的是更晚的时刻，证不出
    // 提交何时产生。另一份日志里可能就有那笔提交本身——调用方先查它，再退回。
    if (!/^commit/.test(entries[index].message)) return null;
    return { epoch: entries[index].epoch, basis: 'exact' };
  }
  while (isAmend(entries[index])) {
    const before = entries[index].oldSha;
    let previous = -1;
    for (let i = index - 1; i >= 0; i -= 1) {
      if (entries[i].newSha === before) {
        previous = i;
        break;
      }
    }
    if (previous === -1) return null;
    index = previous;
  }
  // Any non-amend commit operation records the real committer date — `commit:`, `commit (initial):`,
  // `commit (merge):` and so on; only the amend rewrote it. Anything else is not the commit itself.
  // 任何非 amend 的提交操作记的都是真实提交者时间——`commit:`、`commit (initial):`、`commit (merge):`
  // 等；只有 amend 改写过它。其它操作不是那笔提交本身。
  if (!/^commit/.test(entries[index].message)) return null;
  return { epoch: entries[index].epoch, basis: 'exact' };
}

// The older, looser reading, kept for commits whose creation this clone never logged as a commit
// (a rebase pick, a fetch, a clone): the oldest entry that moved HEAD to the sha, exact — but never
// an amend, whose timestamp is the hook's rewrite rather than a clock.
// 较宽松的旧口径，留给本克隆从未以「提交操作」记录过其产生的提交（rebase 摘取、fetch、clone）：取最早
// 把 HEAD 挪到该 sha 的条目，标 exact——但绝不取 amend，它的时间戳是钩子的改写而不是时钟。
function fallbackBumpMoment(entries, sha) {
  const hit = entries.find((entry) => entry.newSha === sha);
  if (!hit || /^commit \(amend\)/.test(hit.message)) return null;
  return { epoch: hit.epoch, basis: 'exact' };
}

// A linked worktree has its own HEAD log; a commit made in the main checkout — or a worktree entered
// on someone else's branch — leaves only a `checkout` entry here and the commit itself in the common
// log. Look for a proven creation in every log first, so a later checkout cannot stand in for it.
// 链接 worktree 有它自己的 HEAD 日志；在主检出里做的提交——或在别人的分支上进入 worktree——在本日志里
// 只留下一条 `checkout`，提交本身在公共日志。先在每一份日志里找可证明的创建时刻，免得一条更晚的 checkout
// 顶替了它。
function realBumpTime(consumerDir, bump) {
  const files = [];
  const own = reflogPath(consumerDir, 'HEAD');
  if (own) files.push(own);
  const common = gitOrNull(consumerDir, ['rev-parse', '--git-common-dir']);
  if (common) {
    const shared = path.resolve(consumerDir, common, 'logs/HEAD');
    if (!files.includes(shared)) files.push(shared);
  }
  const logs = files.map((file) => parseRawReflog(file));
  for (const entries of logs) {
    const proven = bumpMomentFromReflog(entries, bump.sha);
    if (proven) return proven;
  }
  for (const entries of logs) {
    const loose = fallbackBumpMoment(entries, bump.sha);
    if (loose) return loose;
  }
  return { epoch: epochOf(bump.commitDate), basis: 'approx' };
}

function tagInfo(contractDir, tag) {
  const out = gitOrNull(contractDir, [
    'for-each-ref',
    // `for-each-ref` has no `%x09`; its own tab escape is `%09`.
    // `for-each-ref` 不认 `%x09`，它自己的制表转义是 `%09`。
    '--format=%(objecttype)%09%(creatordate:iso-strict)',
    `refs/tags/${tag}`,
  ]);
  if (!out) return null;
  const [objecttype, created] = out.split('\t');
  return { objecttype, createdEpoch: epochOf(created) };
}

// A fetch is the only local trace of a tag that reached the remote. The message names the tag
// refspec, and its timestamp is an upper bound on the push.
// fetch 是 tag 到达远端的唯一本地痕迹：消息里带着该 tag 的 refspec，时间即推送的上界。
function tagFetchBound(contractDir, tag) {
  const out = gitOrNull(contractDir, [
    'reflog',
    'show',
    '--all',
    '--date=iso-strict',
    '--format=%gd%x09%gs',
  ]);
  if (!out) return null;
  const needle = `refs/tags/${tag}`;
  let earliest = null;
  for (const line of out.split(/\r?\n/)) {
    const [selector, subject] = line.split('\t');
    if (!subject || !subject.includes(needle)) continue;
    const m = (selector || '').match(/@\{(.+)\}$/);
    if (!m) continue;
    const epoch = epochOf(m[1]);
    if (earliest === null || epoch < earliest) earliest = epoch;
  }
  return earliest;
}

function githubSlug(dir) {
  const url = gitOrNull(dir, ['remote', 'get-url', 'origin']);
  if (!url) return null;
  const m = url.match(/github\.com[:/]+([^/]+)\/([^/]+?)(?:\.git)?$/);
  return m ? `${m[1]}/${m[2]}` : null;
}

function ghRuns(slug) {
  try {
    const out = execFileSync(
      'gh',
      ['run', 'list', '-R', slug, '--limit', '200', '--json', 'headBranch,event,createdAt,headSha,workflowName,conclusion'],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    return JSON.parse(out);
  } catch {
    return null;
  }
}

function judge(timeline) {
  const lower = epochOf(timeline.contract.created);
  const bump = epochOf(timeline.bump.time);
  const push = timeline.consumerPush.time ? epochOf(timeline.consumerPush.time) : null;
  const cBasis = timeline.contract.pushedBasis;
  const upper = cBasis === 'unknown' || !timeline.contract.pushed ? null : epochOf(timeline.contract.pushed);

  const findings = [];
  let order = 'ok';

  if (push === null) {
    return {
      status: 'UNDECIDED',
      order: 'undecided',
      sameDay: 'undecided',
      findings: ['the pin bump is not on the tracked remote branch, so there is no consumer push to compare'],
    };
  }

  if (cBasis === 'exact') {
    if (upper <= bump) {
      order = 'ok';
    } else {
      order = 'violation';
      findings.push(
        `the contract tag was pushed at ${formatLocal(upper)}, after the pin bump at ${formatLocal(bump)}`,
      );
    }
  } else if (upper !== null && upper <= bump) {
    // push <= upper <= bump
    // 推送 ≤ 上界 ≤ bump
    order = 'ok';
  } else if (lower > bump) {
    // push >= lower > bump
    // 推送 ≥ 下界 > bump
    order = 'violation';
    findings.push(
      `the contract tag was created at ${formatLocal(lower)}, after the pin bump at ${formatLocal(bump)} — ` +
        'the contract push cannot have come first',
    );
  } else {
    order = 'undecided';
  }

  if (push < bump) {
    order = 'violation';
    findings.push(
      `the consumer was pushed at ${formatLocal(push)}, before its pin bump at ${formatLocal(bump)}`,
    );
  }

  let sameDay = 'ok';
  const bumpDay = localDay(bump);
  const pushDay = localDay(push);
  if (bumpDay !== pushDay) {
    sameDay = 'violation';
    findings.push(
      `the pin bump (${formatLocal(bump)}) and the consumer push (${formatLocal(push)}) are not on the same day`,
    );
  }
  if (upper === null) {
    if (sameDay === 'ok') sameDay = 'undecided';
  } else if (cBasis === 'exact') {
    if (bumpDay !== localDay(upper)) {
      sameDay = 'violation';
      findings.push(
        `the contract tag push (${formatLocal(upper)}) and the pin bump (${formatLocal(bump)}) are not on the same day`,
      );
    }
  } else {
    const lowerDay = localDay(lower);
    const upperDay = localDay(upper);
    if (bumpDay < lowerDay || bumpDay > upperDay) {
      sameDay = 'violation';
      findings.push(
        `the tag's push window (${formatLocal(lower)} .. ${formatLocal(upper)}) does not include the bump's day`,
      );
    } else if (lowerDay !== upperDay) {
      if (sameDay === 'ok') sameDay = 'undecided';
    }
  }

  let status = 'OK';
  if (order === 'violation' || sameDay === 'violation') status = 'VIOLATION';
  else if (order === 'undecided' || sameDay === 'undecided') status = 'UNDECIDED';
  else if (timeline.bump.basis !== 'exact' || timeline.consumerPush.basis !== 'exact') {
    status = 'UNDECIDED';
    findings.push('at least one moment is a commit date, which the post-commit hook may have rewritten');
  }

  if (status === 'UNDECIDED' && !findings.length) {
    findings.push('the tag push is only bounded, and that bound straddles the comparison point');
  }
  return { status, order, sameDay, findings };
}

const SELF_TEST_CASES = [
  {
    name: 'compliant, all exact',
    timeline: {
      contract: { created: '2026-11-08T08:00:00+08:00', pushed: '2026-11-08T08:05:00+08:00', pushedBasis: 'exact' },
      bump: { time: '2026-11-08T09:00:00+08:00', basis: 'exact' },
      consumerPush: { time: '2026-11-08T09:10:00+08:00', basis: 'exact' },
    },
    expect: 'OK',
  },
  {
    name: 'bump before the contract push',
    timeline: {
      contract: { created: '2026-11-08T09:00:00+08:00', pushed: '2026-11-08T11:00:00+08:00', pushedBasis: 'exact' },
      bump: { time: '2026-11-08T10:30:00+08:00', basis: 'exact' },
      consumerPush: { time: '2026-11-08T11:30:00+08:00', basis: 'exact' },
    },
    expect: 'VIOLATION',
  },
  {
    name: 'consumer pushed before its bump',
    timeline: {
      contract: { created: '2026-11-08T07:00:00+08:00', pushed: '2026-11-08T08:00:00+08:00', pushedBasis: 'exact' },
      bump: { time: '2026-11-08T09:00:00+08:00', basis: 'exact' },
      consumerPush: { time: '2026-11-08T08:30:00+08:00', basis: 'exact' },
    },
    expect: 'VIOLATION',
  },
  {
    name: 'next day',
    timeline: {
      contract: { created: '2026-11-08T23:50:00+08:00', pushed: '2026-11-08T23:55:00+08:00', pushedBasis: 'exact' },
      bump: { time: '2026-11-09T00:10:00+08:00', basis: 'exact' },
      consumerPush: { time: '2026-11-09T00:20:00+08:00', basis: 'exact' },
    },
    expect: 'VIOLATION',
  },
  {
    name: 'upper bound straddles the bump',
    timeline: {
      contract: { created: '2026-11-08T08:00:00+08:00', pushed: '2026-11-08T13:00:00+08:00', pushedBasis: 'upper-bound' },
      bump: { time: '2026-11-08T11:00:00+08:00', basis: 'exact' },
      consumerPush: { time: '2026-11-08T11:30:00+08:00', basis: 'exact' },
    },
    expect: 'UNDECIDED',
  },
  {
    name: 'upper bound before the bump, so the order is proven',
    timeline: {
      contract: { created: '2026-11-08T08:00:00+08:00', pushed: '2026-11-08T10:00:00+08:00', pushedBasis: 'upper-bound' },
      bump: { time: '2026-11-08T11:00:00+08:00', basis: 'exact' },
      consumerPush: { time: '2026-11-08T11:30:00+08:00', basis: 'exact' },
    },
    expect: 'OK',
  },
  {
    name: 'tag created after the bump, push unknown',
    timeline: {
      contract: { created: '2026-11-08T12:00:00+08:00', pushed: null, pushedBasis: 'unknown' },
      bump: { time: '2026-11-08T11:00:00+08:00', basis: 'exact' },
      consumerPush: { time: '2026-11-08T11:30:00+08:00', basis: 'exact' },
    },
    expect: 'VIOLATION',
  },
  {
    name: 'everything unknown after the tag was created',
    timeline: {
      contract: { created: '2026-11-08T08:00:00+08:00', pushed: null, pushedBasis: 'unknown' },
      bump: { time: '2026-11-08T11:00:00+08:00', basis: 'exact' },
      consumerPush: { time: '2026-11-08T11:30:00+08:00', basis: 'exact' },
    },
    expect: 'UNDECIDED',
  },
  {
    name: 'commit dates only',
    timeline: {
      contract: { created: '2026-11-08T08:00:00+08:00', pushed: '2026-11-08T08:05:00+08:00', pushedBasis: 'exact' },
      bump: { time: '2026-11-08T21:00:00+08:00', basis: 'approx' },
      consumerPush: { time: '2026-11-08T21:10:00+08:00', basis: 'exact' },
    },
    expect: 'UNDECIDED',
  },
];

// The judge fixtures above exercise `judge()`; the defect this file was fixed for lives in how the
// bump moment is read out of the reflog, which `judge()` never sees. These fixtures run that reader
// directly. The two constants are the seconds of the incident this reader was fixed for — the real
// commit time and the value the hook's amend mapped it to (2026-11-23, KeelBase `2ea7f597`).
// 上面的判决夹具只练 `judge()`；本文件修的那个缺陷在「从 reflog 读出 bump 时刻」这一步，而 `judge()`
// 看不到它。下面这些夹具直接跑那个读数器。两个常量取自本读数器因之而修的那次事故——真实提交时刻，以及
// 钩子 amend 映射出的那个（2026-11-23，KeelBase `2ea7f597`）。
const REAL_SECOND = epochOf('2026-11-23T13:33:06+08:00');
const REWRITTEN_SECOND = epochOf('2026-11-23T23:53:06+08:00');
const SHA_P = '1'.repeat(40);
const SHA_A = '2'.repeat(40);
const SHA_B = '3'.repeat(40);
const SHA_C = '4'.repeat(40);

const REFLOG_TEST_CASES = [
  {
    name: 'unamended commit: the oldest matching entry, exact',
    run: () =>
      bumpMomentFromReflog(
        [
          { oldSha: SHA_P, newSha: SHA_A, epoch: REAL_SECOND, message: 'commit: move the pin' },
          { oldSha: SHA_A, newSha: SHA_B, epoch: REWRITTEN_SECOND, message: 'checkout: moving from wip to main' },
        ],
        SHA_A,
      ),
    expect: { epoch: REAL_SECOND, basis: 'exact' },
  },
  {
    name: 'hook-amended commit: the pre-amend commit time, never the amend',
    run: () =>
      bumpMomentFromReflog(
        [
          // A repository's first commit says `commit (initial)`, not `commit`.
          // 仓库的第一笔提交写作 `commit (initial)`，不是 `commit`。
          { oldSha: SHA_P, newSha: SHA_A, epoch: REAL_SECOND, message: 'commit (initial): move the pin' },
          { oldSha: SHA_A, newSha: SHA_B, epoch: REWRITTEN_SECOND, message: 'commit (amend): move the pin' },
        ],
        SHA_B,
      ),
    expect: { epoch: REAL_SECOND, basis: 'exact' },
  },
  {
    name: 'amend on a commit that arrived by checkout: no proven reading',
    run: () =>
      bumpMomentFromReflog(
        [
          { oldSha: SHA_P, newSha: SHA_A, epoch: REAL_SECOND, message: 'checkout: moving from main to wip' },
          { oldSha: SHA_A, newSha: SHA_B, epoch: REWRITTEN_SECOND, message: 'commit (amend): move the pin' },
        ],
        SHA_B,
      ),
    expect: null,
  },
  {
    name: 'checkout-only hit: the proven reader refuses it',
    run: () =>
      bumpMomentFromReflog(
        [{ oldSha: SHA_P, newSha: SHA_A, epoch: REAL_SECOND, message: 'checkout: moving from main to wip' }],
        SHA_A,
      ),
    expect: null,
  },
  {
    name: 'checkout-only hit: the loose fallback keeps the old reading',
    run: () =>
      fallbackBumpMoment(
        [{ oldSha: SHA_P, newSha: SHA_A, epoch: REAL_SECOND, message: 'checkout: moving from main to wip' }],
        SHA_A,
      ),
    expect: { epoch: REAL_SECOND, basis: 'exact' },
  },
  {
    name: 'loose fallback never takes an amend timestamp',
    run: () =>
      fallbackBumpMoment(
        [{ oldSha: SHA_A, newSha: SHA_B, epoch: REWRITTEN_SECOND, message: 'commit (amend): move the pin' }],
        SHA_B,
      ),
    expect: null,
  },
  {
    name: 'amended twice: still the original commit time',
    run: () =>
      bumpMomentFromReflog(
        [
          { oldSha: SHA_P, newSha: SHA_A, epoch: REAL_SECOND, message: 'commit: move the pin' },
          { oldSha: SHA_A, newSha: SHA_B, epoch: REWRITTEN_SECOND, message: 'commit (amend): move the pin' },
          { oldSha: SHA_B, newSha: SHA_C, epoch: REWRITTEN_SECOND + 10, message: 'commit (amend): move the pin' },
        ],
        SHA_C,
      ),
    expect: { epoch: REAL_SECOND, basis: 'exact' },
  },
  {
    name: 'amend whose pre-image is not in the log: no exact reading',
    run: () =>
      bumpMomentFromReflog(
        [{ oldSha: SHA_A, newSha: SHA_B, epoch: REWRITTEN_SECOND, message: 'commit (amend): move the pin' }],
        SHA_B,
      ),
    expect: null,
  },
  {
    name: 'sha absent from the log: no exact reading',
    run: () =>
      bumpMomentFromReflog(
        [{ oldSha: SHA_P, newSha: SHA_A, epoch: REAL_SECOND, message: 'commit: move the pin' }],
        SHA_C,
      ),
    expect: null,
  },
  {
    name: 'no HEAD log at all: the commit date, marked approx',
    // realBumpTime() is run rather than the pure reader: a directory that is not a clone has no
    // reflog, so this is the fallback path.
    // 这里直接跑 realBumpTime()：不是克隆的目录没有 reflog，跑的就是回退那条路。
    run: () =>
      realBumpTime(path.join(SCRIPT_DIR, '.self-test-no-clone'), {
        sha: SHA_B,
        commitDate: '2026-11-08T10:00:00+08:00',
      }),
    expect: { epoch: epochOf('2026-11-08T10:00:00+08:00'), basis: 'approx' },
  },
];

function selfTest() {
  let failures = 0;
  for (const testCase of SELF_TEST_CASES) {
    const verdict = judge(testCase.timeline);
    const ok = verdict.status === testCase.expect;
    if (!ok) failures += 1;
    process.stdout.write(`${ok ? 'PASS' : 'FAIL'}  ${testCase.name}: ${verdict.status} (expected ${testCase.expect})\n`);
    if (!ok) for (const f of verdict.findings) process.stdout.write(`      ${f}\n`);
  }
  const show = (moment) => (moment === null ? 'no reading' : `${moment.epoch} (${moment.basis})`);
  for (const testCase of REFLOG_TEST_CASES) {
    const got = testCase.run();
    const ok =
      testCase.expect === null
        ? got === null
        : Boolean(got) && got.epoch === testCase.expect.epoch && got.basis === testCase.expect.basis;
    if (!ok) failures += 1;
    process.stdout.write(`${ok ? 'PASS' : 'FAIL'}  ${testCase.name}: ${show(got)} (expected ${show(testCase.expect)})\n`);
  }
  if (failures) {
    process.stdout.write(`self-test: ${failures} case(s) did not match\n`);
    process.exit(1);
  }
  process.stdout.write(
    `self-test: ${SELF_TEST_CASES.length}/${SELF_TEST_CASES.length} judge and ` +
      `${REFLOG_TEST_CASES.length}/${REFLOG_TEST_CASES.length} reflog cases match\n`,
  );
  process.exit(0);
}

function parseArgs(argv) {
  const opts = {
    consumer: REPO_ROOT,
    contract: process.env.KEELBASE_CONTRACT_DIR || '',
    tag: '',
    bumpCommit: '',
    branch: 'main',
    online: false,
    strict: false,
    json: false,
    fixture: '',
    selfTest: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const need = (name) => {
      if (i + 1 >= argv.length) throw new Error(`${name} needs a value`);
      i += 1;
      return argv[i];
    };
    switch (arg) {
      case '--consumer': opts.consumer = path.resolve(need(arg)); break;
      case '--contract': opts.contract = path.resolve(need(arg)); break;
      case '--tag': opts.tag = need(arg); break;
      case '--bump-commit': opts.bumpCommit = need(arg); break;
      case '--branch': opts.branch = need(arg); break;
      case '--online': opts.online = true; break;
      case '--strict': opts.strict = true; break;
      case '--json': opts.json = true; break;
      case '--fixture': opts.fixture = path.resolve(need(arg)); break;
      case '--self-test': opts.selfTest = true; break;
      case '-h':
      case '--help': usage(); process.exit(0); break;
      default: throw new Error(`unknown argument: ${arg} (try --help)`);
    }
  }
  return opts;
}

function main() {
  let opts;
  try {
    opts = parseArgs(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exit(2);
  }
  if (opts.selfTest) selfTest();

  if (opts.fixture) {
    const timeline = JSON.parse(fs.readFileSync(opts.fixture, 'utf8'));
    const verdict = judge(timeline);
    printReport(opts, timeline, verdict, { fixture: opts.fixture });
    process.exit(opts.strict && verdict.status === 'VIOLATION' ? 1 : 0);
  }

  if (!isGitClone(opts.consumer)) {
    process.stderr.write(`--consumer is not a git clone: ${opts.consumer}\n`);
    process.exit(2);
  }
  const tag = opts.tag || readPin(opts.consumer);
  if (!tag) {
    process.stderr.write('cannot read the contract pin from the consumer workflows; pass --tag\n');
    process.exit(2);
  }
  if (!opts.contract) {
    opts.contract = path.resolve(opts.consumer, '..', 'keelbase-contract');
  }
  if (!isGitClone(opts.contract)) {
    process.stderr.write(`--contract is not a git clone: ${opts.contract}\n`);
    process.exit(2);
  }

  const tagData = tagInfo(opts.contract, tag);
  if (!tagData) {
    process.stderr.write(`the contract clone does not know tag ${tag}\n`);
    process.exit(2);
  }

  const bump = opts.bumpCommit
    ? { sha: git(opts.consumer, ['rev-parse', opts.bumpCommit]), commitDate: git(opts.consumer, ['log', '-1', '--format=%cI', opts.bumpCommit]), subject: git(opts.consumer, ['log', '-1', '--format=%s', opts.bumpCommit]) }
    : findBumpCommit(opts.consumer, tag);
  if (!bump) {
    process.stderr.write(`cannot find the commit that moved the pin to ${tag}; pass --bump-commit\n`);
    process.exit(2);
  }

  const bumpTime = realBumpTime(opts.consumer, bump);
  const pushed = firstPushCarrying(opts.consumer, `refs/remotes/origin/${opts.branch}`, bump.sha);

  const timeline = {
    contract: {
      tag,
      objecttype: tagData.objecttype,
      created: formatLocal(tagData.createdEpoch),
      pushed: null,
      pushedBasis: 'unknown',
      source: '',
    },
    bump: {
      sha: bump.sha,
      subject: bump.subject,
      time: formatLocal(bumpTime.epoch),
      basis: bumpTime.basis,
      commitDate: bump.commitDate,
    },
    consumerPush: {
      time: pushed ? formatLocal(pushed.epoch) : null,
      basis: pushed ? 'exact' : 'unknown',
      tip: pushed ? pushed.tip : null,
    },
  };

  if (opts.online) {
    const contractSlug = githubSlug(opts.contract);
    const runs = contractSlug ? ghRuns(contractSlug) : null;
    if (runs) {
      const tagRun = runs.find((r) => r.event === 'push' && r.headBranch === tag);
      if (tagRun) {
        timeline.contract.pushed = formatLocal(epochOf(tagRun.createdAt));
        timeline.contract.pushedBasis = 'exact';
        timeline.contract.source = `GitHub Actions run for the tag push (${tagRun.workflowName}, ${tagRun.conclusion})`;
      } else {
        process.stderr.write(`[online] no tag-push run found for ${tag}; the tag predates the tag workflow or ran elsewhere\n`);
      }
    } else {
      process.stderr.write(
        `[online] could not read runs for ${contractSlug || 'the contract remote'}; keeping local evidence\n`,
      );
    }
    if (timeline.consumerPush.tip) {
      const consumerSlug = githubSlug(opts.consumer);
      const consumerRuns = consumerSlug ? ghRuns(consumerSlug) : null;
      const pushRun = consumerRuns && consumerRuns.find((r) => r.event === 'push' && r.headSha === timeline.consumerPush.tip);
      if (pushRun) {
        timeline.consumerPush.runAt = formatLocal(epochOf(pushRun.createdAt));
      }
    }
  }

  if (timeline.contract.pushedBasis === 'unknown') {
    const bound = tagFetchBound(opts.contract, tag);
    if (bound !== null) {
      timeline.contract.pushed = formatLocal(bound);
      timeline.contract.pushedBasis = 'upper-bound';
      timeline.contract.source = 'reflog of a fetch in this clone (the tag was on the remote by then)';
    }
  }
  if (timeline.contract.pushedBasis === 'exact' && !timeline.contract.source) {
    timeline.contract.source = 'GitHub Actions run for the tag push';
  }

  const verdict = judge(timeline);
  printReport(opts, timeline, verdict, { tag, consumer: opts.consumer, contract: opts.contract });

  const exitCode = opts.strict && verdict.status === 'VIOLATION' ? 1 : 0;
  process.exit(exitCode);
}

function printReport(opts, timeline, verdict, meta) {
  if (opts.json) {
    process.stdout.write(`${JSON.stringify({ meta, timeline, verdict }, null, 2)}\n`);
    return;
  }
  const out = [];
  const basisNote = (basis) =>
    ({ exact: 'exact', 'upper-bound': 'upper bound', approx: 'commit date (may be hook-rewritten)', unknown: 'unknown' }[basis] || basis);
  out.push(`CT-4 push order — ${timeline.contract.tag || '(fixture)'}`);
  if (meta.consumer) out.push(`  consumer: ${meta.consumer}`);
  if (meta.contract) out.push(`  contract: ${meta.contract}`);
  if (meta.fixture) out.push(`  fixture:  ${meta.fixture}`);
  out.push('');
  const createdBasis = timeline.contract.objecttype === 'tag'
    ? 'taggerdate'
    : timeline.contract.objecttype
      ? 'commit date of a lightweight tag'
      : 'fixture time';
  out.push(`  contract tag created  ${timeline.contract.created}  (${createdBasis}; lower bound on the push)`);
  out.push(`  contract tag pushed   ${timeline.contract.pushed || '(unknown)'}  (${basisNote(timeline.contract.pushedBasis)}${timeline.contract.source ? `: ${timeline.contract.source}` : ''})`);
  out.push(`  consumer pin bump     ${timeline.bump.time}  (${basisNote(timeline.bump.basis)}${timeline.bump.sha ? `; ${timeline.bump.sha.slice(0, 7)} ${timeline.bump.subject}` : ''})`);
  out.push(`  consumer push         ${timeline.consumerPush.time || '(not on origin/' + (meta.branch || 'main') + ')'}  (${basisNote(timeline.consumerPush.basis)})`);
  if (timeline.consumerPush.runAt) out.push(`  (corroborating CI run at ${timeline.consumerPush.runAt})`);
  out.push('');
  out.push(`  verdict: ${verdict.status}`);
  for (const finding of verdict.findings) out.push(`    - ${finding}`);
  process.stdout.write(`${out.join('\n')}\n`);
}

main();
