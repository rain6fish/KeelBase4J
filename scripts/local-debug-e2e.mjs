// SPDX-License-Identifier: Apache-2.0
//
// The local debug mode's end-to-end reading: a deployment that carries a provider, started with no API
// key, walks the whole governance chain through the deterministic seams — and refuses a free-form
// question in so many words instead of inventing an answer.
//
// It is called by `scripts/demo-local-debug.sh`, which builds the demo deployment, starts it with the
// switch on, and mints the delegation tokens. Tokens are passed in because the runtime accepts nothing
// else; there is no back door, not even for a debug mode.
//
// Every assertion is a pair where the claim allows one: the write is held before approval and executed
// after it, and the unsupported question is refused while the supported one is routed.
//
//   node scripts/local-debug-e2e.mjs <base-url> <alice-token> <carol-token>
//
// 本地调试模式的端到端读数：一个带着 provider、却在没有 API key 的情况下启动的部署，经确定性接缝走完整条
// 治理链 —— 并对自由问答明确说「不支持」，而不是编一个答案。
//
// 它由 `scripts/demo-local-debug.sh` 调用——后者负责构建 demo 部署、打开开关启动、签发委托令牌。令牌是
// 传进来的，因为运行时只认这一种入口；即便调试模式也没有后门。
//
// 每一条断言，只要主张允许，都是成对的：批准前写被闸住、批准后才执行；不支持的问答被拒绝，而支持的问答正常路由。
//
//   node scripts/local-debug-e2e.mjs <base-url> <alice-token> <carol-token>

const [base, aliceToken, carolToken] = process.argv.slice(2)

const die = (message, hint) => {
  console.error(`FAIL ${message}`)
  if (hint) console.error(`     ${hint}`)
  process.exit(1)
}

if (!base || !aliceToken || !carolToken) {
  die('usage: node scripts/local-debug-e2e.mjs <base-url> <alice-token> <carol-token>')
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

// One call, unwrapped from the frozen api-response envelope.
//
// 一次调用，拆掉冻结的 api-response 信封。
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

const effects = async (token) => {
  const res = await api('/ai/tool-effects?userId=alice&limit=100', token)
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

try {
  // ── the write path: proposed, held, approved, executed, audited ─────────────────────────────
  console.log('== local debug mode · R3 write ==')
  const effectsBefore = (await effects(aliceToken)).length
  const pendingBefore = (await pendingItems(aliceToken)).length

  const proposed = await chat(aliceToken, '创建跟进任务，提醒续约', 1)
  const proposedReply = String((proposed.data && proposed.data.reply) || '')
  ok(proposed.status === 200 && Array.isArray(proposed.data && proposed.data.toolCalls)
      && proposed.data.toolCalls.includes('create_followup'),
    `the deterministic rules route the write (${proposed.status}, ${(proposed.data && proposed.data.toolCalls) || 'n/a'})`)
  ok(proposedReply.includes('waiting for your confirmation'),
    'the reply reports it as waiting, not done')
  ok((proposed.data && proposed.data.provider) === 'deterministic'
      && (proposed.data && proposed.data.model) === 'none',
    `the answer names itself as deterministic rather than a model (provider=${proposed.data && proposed.data.provider}, model=${proposed.data && proposed.data.model})`)

  const pending = await pendingItems(aliceToken)
  const writeItem = newest(pending, (item) => item.toolName === 'create_followup')
  ok(pending.length === pendingBefore + 1 && writeItem !== undefined,
    'exactly one new pending write is held for the operator')
  ok((await effects(aliceToken)).length === effectsBefore,
    'nothing is written before the operator approves')

  const approved = await api(`/ai/confirmations/${writeItem.token}`, aliceToken,
    { method: 'POST', body: { decision: 'approve' } })
  ok(approved.status === 200 && approved.data && approved.data.status === 'executed'
      && approved.data.effectId !== null && approved.data.effectId !== undefined,
    `approval executed the write and recorded an effect (effect ${approved.data && approved.data.effectId})`)

  const afterApproval = await effects(aliceToken)
  const effect = afterApproval.find((item) => item.id === approved.data.effectId)
  ok(afterApproval.length === effectsBefore + 1 && effect !== undefined
      && effect.resultType === 'follow_up',
    'the side-effect row names the follow-up and is the only new one')

  const verify = await api('/audit/verify', carolToken)
  ok(verify.status === 200 && verify.data && verify.data.valid === true,
    'the audit hash chain verifies after the write')

  const rows = await auditRows('userId=alice&limit=100')
  const executed = newest(rows, (row) => row.tool === 'create_followup'
    && row.outcome === 'executed')
  ok(executed !== undefined,
    'the audit chain carries the executed call, naming the tool')

  // ── the unsupported case: a free-form question is refused, not answered ────────────────────
  console.log('== local debug mode · unsupported free-form question ==')
  const question = await chat(aliceToken, '今天天气怎么样？', 1)
  const questionReply = String((question.data && question.data.reply) || '')
  ok(question.status === 200,
    `the endpoint answers rather than failing the request (${question.status})`)
  ok(questionReply.includes('Offline debug mode') && questionReply.includes('free-form answer'),
    `the refusal says offline debug mode does not support it: ${questionReply}`)
  ok(!question.data || !('toolCalls' in question.data),
    'no tool was proposed for the question')
  ok((question.data && question.data.provider) === 'deterministic'
      && (question.data && question.data.model) === 'none',
    'and it is not dressed up as a model reply')

  const total = checks
  if (failures === 0) {
    console.log(`PASS local-debug: ${total} checks`)
  } else {
    console.log(`FAIL local-debug: ${failures} of ${total} checks failed`)
    process.exit(1)
  }
} catch (error) {
  console.error(`FAIL local-debug: ${error && error.stack ? error.stack : error}`)
  process.exit(1)
}
