// SPDX-License-Identifier: Apache-2.0
//
// The PM reference project's end-to-end reading: one process drives the running runtime through the
// whole governance loop and asserts each beat.
//
// It is called by `scripts/demo-pm-reference.sh`, which builds the runtime, starts it, and mints the
// delegation tokens. The tokens are passed in rather than signed here because the runtime accepts
// nothing else: a caller proves who it is with a token, and the runtime maps the subject to a local
// role. There is no back door for a demo.
//
// Every assertion is a pair where the claim allows one: the normal path has a reading, and the
// refused path has one too. A demo that only shows the happy path proves the feature exists, not
// that it is governed.
//
//   node scripts/pm-reference-e2e.mjs <base-url> <alice-token> <carol-token> <bob-token>
//
// PM 参考项目的端到端读数：一个进程驱动运行中的运行时走完整条治理闭环，并逐拍断言。
//
// 它由 `scripts/demo-pm-reference.sh` 调用——后者负责构建、启动、签发委托令牌。令牌是传进来的、不是在这里
// 签的，因为运行时**只认这一种入口**：调用方用令牌证明自己是谁，运行时才把主体映射成本地角色。演示没有后门。
//
// 每一条断言，只要主张允许，都是**成对**的：正常路径有读数，被拒路径也有读数。只演示顺路的 demo 证明的是
// 「功能存在」，不是「它受治理」。
//
//   node scripts/pm-reference-e2e.mjs <base-url> <alice-token> <carol-token> <bob-token>

const [base, aliceToken, carolToken, bobToken] = process.argv.slice(2)

const die = (message, hint) => {
  console.error(`FAIL ${message}`)
  if (hint) console.error(`     ${hint}`)
  process.exit(1)
}

if (!base || !aliceToken || !carolToken || !bobToken) {
  die('usage: node scripts/pm-reference-e2e.mjs <base-url> <alice-token> <carol-token> <bob-token>')
}

let checks = 0
let failures = 0

const ok = (condition, message) => {
  checks += 1
  if (condition) {
    console.log(`  ok   ${message}`)
  } else {
    failures += 1
    console.error(`  FAIL ${message}`)
  }
}

// One call, unwrapped. The runtime answers every REST response in the frozen api-response envelope,
// so the payload is `data`; an error carries no data and is judged by its status.
//
// 一次调用，拆掉信封。运行时对每条 REST 响应都用冻结的 api-response 信封作答，故载荷是 `data`；错误没有
// data，按状态码判定。
async function api(path, token, { method = 'GET', body } = {}) {
  const headers = { Authorization: `Bearer ${token}` }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch(`${base}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let parsed = null
  try {
    parsed = text ? JSON.parse(text) : null
  } catch {
    parsed = null
  }
  const data = parsed && typeof parsed === 'object' && 'data' in parsed ? parsed.data : parsed
  return { status: response.status, data, body: parsed }
}

const chat = (token, message, targetId) =>
  api('/ai/chat', token, { method: 'POST', body: { message, targetId } })

const pendingItems = async (token) => {
  const res = await api('/ai/my/confirmations?status=pending', token)
  return res.status === 200 && Array.isArray(res.data) ? res.data : []
}

const adminQueue = async () => {
  const res = await api('/ai/confirmations?page=1&limit=100', carolToken)
  return res.status === 200 && res.data && Array.isArray(res.data.items) ? res.data.items : []
}

const auditRows = async (query) => {
  const res = await api(`/audit/logs?${query}`, carolToken)
  return res.status === 200 && Array.isArray(res.data) ? res.data : []
}

const newest = (items, predicate) => {
  let picked = null
  for (const item of items) {
    if (!predicate(item)) continue
    if (picked === null || String(item.createdAt) > String(picked.createdAt)) picked = item
  }
  return picked
}

const projectTasks = async (projectId) => {
  const res = await api(`/pm/projects/${projectId}/tasks`, aliceToken)
  return res.status === 200 && Array.isArray(res.data) ? res.data : []
}

try {
  // ── the read path: rows are ranged, and an R1 tool runs without a confirmation ──────────────
  console.log('== PM reference project · read path ==')
  const aliceProjects = await api('/pm/projects', aliceToken)
  ok(aliceProjects.status === 200 && Array.isArray(aliceProjects.data) && aliceProjects.data.length > 0,
    `alice sees her projects (${aliceProjects.status}, ${Array.isArray(aliceProjects.data) ? aliceProjects.data.length : 'n/a'} row(s))`)

  const atlas = (aliceProjects.data || []).find((p) => p.name === 'Atlas Launch')
  ok(atlas !== undefined, 'the seeded project "Atlas Launch" is visible to its owner')
  if (!atlas) die('the demo seed is missing; start the runtime without the test profile')
  const projectId = atlas.id

  const bobProjects = await api('/pm/projects', bobToken)
  ok(bobProjects.status === 200 && !(bobProjects.data || []).some((p) => p.name === 'Atlas Launch'),
    "bob's list does not contain alice's project — the row range is applied to the PM table too")

  const pendingBeforeRead = (await pendingItems(aliceToken)).length
  const tasksBeforeRead = (await projectTasks(projectId)).length
  const analysis = await chat(aliceToken, '分析项目风险', projectId)
  const reply = String((analysis.data && analysis.data.reply) || '')
  ok(analysis.status === 200 && Array.isArray(analysis.data && analysis.data.toolCalls)
      && analysis.data.toolCalls.includes('analyze_project_risk'),
    'the R1 read is routed to analyze_project_risk')
  ok(reply.includes('level=critical') && reply.includes('overdueTasks=1'),
    `the deterministic reading is reported (${reply.match(/level=\w+/)?.[0]}, ${reply.match(/overdueTasks=\d+/)?.[0]})`)
  ok((await pendingItems(aliceToken)).length === pendingBeforeRead,
    'an R1 read holds nothing for confirmation')
  ok((await projectTasks(projectId)).length === tasksBeforeRead,
    'a read writes no task')

  // ── the confirm path: R3 waits, executes on approval, and a revoke compensates it ──────────
  console.log('== PM reference project · R3 confirmation + revoke ==')
  const pendingBeforeWrite = (await pendingItems(aliceToken)).length
  const tasksBeforeWrite = (await projectTasks(projectId)).length
  const proposed = await chat(aliceToken, '为项目创建一个任务', projectId)
  const proposedReply = String((proposed.data && proposed.data.reply) || '')
  ok(proposedReply.includes('waiting for your confirmation'),
    'the R3 write is proposed and reported as waiting, not done')
  ok((await projectTasks(projectId)).length === tasksBeforeWrite,
    'nothing is written before the operator approves')

  const pending = await pendingItems(aliceToken)
  const writeItem = newest(pending, (item) => item.toolName === 'create_pm_task')
  ok(pending.length === pendingBeforeWrite + 1 && writeItem !== undefined,
    'the operator holds exactly one new pending write')

  const approved = await api(`/ai/confirmations/${writeItem.token}`, aliceToken,
    { method: 'POST', body: { decision: 'approve' } })
  ok(approved.status === 200 && approved.data && approved.data.status === 'executed'
      && approved.data.effectId !== null && approved.data.effectId !== undefined,
    `approval executed the write and recorded an effect (effect ${approved.data && approved.data.effectId})`)
  ok((await projectTasks(projectId)).length === tasksBeforeWrite + 1,
    'the approved write created exactly one task')

  const effects = await api('/ai/tool-effects?userId=alice&limit=100', aliceToken)
  const effect = (effects.data && effects.data.items || []).find((item) => item.id === approved.data.effectId)
  ok(effect !== undefined && effect.resultType === 'pm_task' && effect.revocable === true,
    'the effect ledger names the PM task and marks it revocable')

  const revoked = await api(`/ai/tool-effects/${approved.data.effectId}`, aliceToken, { method: 'DELETE' })
  ok(revoked.status === 200 && revoked.data && revoked.data.revoked === true
      && revoked.data.revokeStatus === 'revoked',
    'the revoke reports the local compensation as done')
  ok((await projectTasks(projectId)).length === tasksBeforeWrite,
    'the revoked task is gone from the live set')

  const verify = await api('/audit/verify', carolToken)
  ok(verify.status === 200 && verify.data && verify.data.valid === true,
    'the audit hash chain verifies after the write and the revoke')

  const aliceAudit = await auditRows('userId=alice&limit=100')
  ok(aliceAudit.some((row) => row.action === 'effect_revoke'
      && String(row.detail).includes('create_pm_task revoked')),
    'the revoke left its own audit line naming the tool')

  // ── the approval path: R4 waits for a second person, and the write belongs to the initiator ─
  console.log('== PM reference project · R4 second-person approval ==')
  const tasksBeforeApproval = (await projectTasks(projectId)).length
  const escalated = await chat(aliceToken, '升级项目', projectId)
  ok(String((escalated.data && escalated.data.reply) || '').includes('needs approval'),
    'the R4 action is reported as needing approval')
  ok((await projectTasks(projectId)).length === tasksBeforeApproval,
    'nothing runs while the second person is being asked')

  const approvalItem = newest(await adminQueue(),
    (item) => item.toolName === 'escalate_project' && item.status === 'pending')
  ok(approvalItem !== undefined,
    'the approval row is visible to the administrator queue (and not to its initiator)')

  const selfApproval = await api(`/ai/confirmations/${approvalItem.token}/approve-by`, aliceToken,
    { method: 'POST', body: { decision: 'approve' } })
  ok(selfApproval.status === 403, `the initiator cannot answer it (${selfApproval.status})`)

  const approvedBy = await api(`/ai/confirmations/${approvalItem.token}/approve-by`, carolToken,
    { method: 'POST', body: { decision: 'approve' } })
  ok(approvedBy.status === 200 && approvedBy.data && approvedBy.data.status === 'executed',
    'a second person approved it and the write ran')
  const afterApproval = await projectTasks(projectId)
  ok(afterApproval.length === tasksBeforeApproval + 1,
    'the approval wrote exactly one task')
  const escalation = afterApproval.find((task) => String(task.title).startsWith('Escalated for'))
  ok(escalation !== undefined && escalation.ownerUserId === 'alice',
    'the write belongs to the initiator, not to the approver')

  const approvalRevoke = await api(`/ai/tool-effects/${approvedBy.data.effectId}`, aliceToken,
    { method: 'DELETE' })
  ok(approvalRevoke.status === 200 && approvalRevoke.data && approvalRevoke.data.revoked === true,
    'the approved write is revocable through the same local compensation')

  // ── the block path: R5 is refused on its risk level, and the refusal is on the chain ───────
  console.log('== PM reference project · R5 block ==')
  const pendingBeforeBlock = (await pendingItems(aliceToken)).length
  const blocked = await chat(aliceToken, '删除项目', projectId)
  ok(String((blocked.data && blocked.data.reply) || '').includes('blocked by the risk policy'),
    'an R5 tool is refused by the risk policy')
  ok((await pendingItems(aliceToken)).length === pendingBeforeBlock,
    'a blocked call asks nobody for a confirmation')
  ok((await api('/pm/projects', aliceToken)).data.some((project) => project.id === projectId),
    'the project is untouched')

  const blockRows = await auditRows('userId=alice&isError=true&limit=100')
  const blockRow = newest(blockRows, (row) => row.tool === 'delete_project')
  ok(blockRow !== undefined && String(blockRow.detail).includes('blocked (risk_policy)')
      && blockRow.outcome === 'blocked',
    'the block left an audit line naming risk_policy')

  // ── the refused pair: another user's row is denied, and the refusal is audited ─────────────
  console.log('== PM reference project · cross-user denial ==')
  const denied = await chat(bobToken, '分析项目风险', projectId)
  ok(denied.status === 403, `a foreign project is refused (${denied.status})`)
  const deniedRows = await auditRows('userId=bob&isError=true&limit=100')
  ok(deniedRows.some((row) => row.tool === 'analyze_project_risk'
      && String(row.detail).includes('refused')),
    'the row refusal is on the audit chain')

  // ── the integration reading: the tool surface and the R5 block are what the health report says ─
  console.log('== PM reference project · integration health ==')
  const status = await api('/keelbase/status', carolToken)
  const tools = status.data && status.data.tools
  const confirmation = status.data && status.data.confirmation
  ok(status.status === 200 && tools && tools.total === 7,
    `the health report counts all seven tools (${tools && tools.total})`)
  ok(tools && tools.riskDistribution && tools.riskDistribution.R5 === 1,
    'the R5 tool is counted at its declared level')
  ok(confirmation && confirmation.effectiveBlocked === 1,
    'the gate reads the R5 tool as blocked')
  ok(tools && Array.isArray(tools.writeWithoutRevoke) && tools.writeWithoutRevoke.length === 0,
    'a never-executing R5 write is not a revoke warning')
  ok(status.data && status.data.health && status.data.health.status === 'healthy',
    'the four dimensions are healthy')

  const total = checks
  if (failures === 0) {
    console.log(`PASS pm-reference: ${total} checks`)
  } else {
    console.log(`FAIL pm-reference: ${failures} of ${total} checks failed`)
    process.exit(1)
  }
} catch (error) {
  console.error(`FAIL pm-reference: ${error && error.stack ? error.stack : error}`)
  process.exit(1)
}
