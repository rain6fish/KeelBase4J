#!/usr/bin/env node

// SPDX-License-Identifier: Apache-2.0

/**
 * Cross-repository check of the typed object key's spelling.
 *
 * A row may name an object at the end of its own sentence (` · object MCP connection #12`). Three
 * parties take part: this runtime's matcher, the host's writer, and the host console's reader. They
 * sit in two repositories, so each used to carry its own copy of the spelling, held together by
 * nothing but the fact that they happened to agree. `conformance/typed-object-key.json` is now the
 * one definition, and this script compares the other two parties against it.
 *
 * What it compares, and why each part matters:
 *   1. the definition's own shape (end-anchored, no trailing-whitespace tolerance, digits 1–18);
 *   2. the host writer's constant (`AUDIT_OBJECT_KIND`) — the word it puts in every row;
 *   3. the console's word-to-type map (`OBJECT_KEY_KINDS`) — the words it can name at all;
 *   4. the console's reader (`recordedTypedObject`) against every row in the definition — including
 *      the ones the runtime rejects. A reader that accepts what the runtime rejects, or loses the
 *      digits of a long key, would make the page and the endpoint answer the same question
 *      differently.
 *
 * The console is imported rather than parsed where possible: it has no host imports, so it loads
 * from a plain `.mjs`. The host checkout is optional; without it the script checks the definition
 * alone and says so, because this repository's CI has no sibling checkout. Report-only by default:
 * a divergence that exists today is printed so it can be fixed, and `--strict` is what turns it into
 * a non-zero exit once it is part of a gate.
 *
 *   node scripts/check-object-key-contract.mjs                     # report; exit 0 when clean
 *   node scripts/check-object-key-contract.mjs --strict            # exit 1 on any divergence
 *   node scripts/check-object-key-contract.mjs --host ../KeelBase-Vue-Pro
 *   KEELBASE_HOST_REPO=/path/to/KeelBase-Vue-Pro node scripts/check-object-key-contract.mjs
 *   node scripts/check-object-key-contract.mjs --self-test         # positive and negative controls
 *   node scripts/check-object-key-contract.mjs --strict --require  # the host must be there (release-gate)
 *   node scripts/check-object-key-contract.mjs --strict --definition-only  # skip the host comparison
 *
 * Exit codes: 0 clean (or report-only); 1 `--strict` and a divergence (or a failed self-test); 2
 * usage or environment (no definition, a host file whose shape changed).
 *
 * ── 中文 ──
 * 带类型对象键拼写的跨仓检查。
 *
 * 一行可能在**自己句末**点名一条对象（` · object MCP connection #12`）。参与的有三方：本运行时的
 * 匹配器、宿主的写入方、宿主控制台的读取器。它们分处两个仓，于是过去各自带着一份拼写，维系它们的只有
 * 「恰好一致」这件事。现在 `conformance/typed-object-key.json` 是那份唯一定义，本脚本把另外两方与它
 * 比对。
 *
 * 比什么、以及每一部分为什么重要：
 *   1. 定义自身的形状（句末锚定、不容忍尾随空白、主键 1–18 位数字）；
 *   2. 宿主写入方的常量（`AUDIT_OBJECT_KIND`）—— 它往每一行里放的那个词；
 *   3. 控制台的词→类型映射（`OBJECT_KEY_KINDS`）—— 它能认得的全部词；
 *   4. 控制台的读取器（`recordedTypedObject`）对定义里的**每一行** —— 包括运行时**拒绝**的那些。
 *      一个接受运行时拒绝之事的读取器、或一个丢掉长主键位数的读取器，会让页面与端点对同一个问题
 *      给出不同答案。
 *
 * 控制台尽量 **import** 而不是解析：它不 import 宿主任何东西，故可直接从 `.mjs` 加载。宿主检出是
 * **可选**的；没有它时脚本只查定义并如实说明 —— 本仓的 CI 没有兄弟仓检出。默认只报告：今天存在的
 * 分叉会被打印出来以便修，而 `--strict` 才把它变成非零退出，等它成为一道闸时使用。
 *
 *   node scripts/check-object-key-contract.mjs                     # 只报告；干净时退出 0
 *   node scripts/check-object-key-contract.mjs --strict            # 有任何分叉即退出 1
 *   node scripts/check-object-key-contract.mjs --host ../KeelBase-Vue-Pro
 *   KEELBASE_HOST_REPO=/path/to/KeelBase-Vue-Pro node scripts/check-object-key-contract.mjs
 *   node scripts/check-object-key-contract.mjs --self-test         # 正向与负向对照
 *   node scripts/check-object-key-contract.mjs --strict --require  # 宿主必须存在（release-gate 用）
 *   node scripts/check-object-key-contract.mjs --strict --definition-only  # 不比对宿主
 *
 * 退出码：0 干净（或只报告）；1 `--strict` 且有分叉（或自检失败）；2 用法 / 环境问题（没有定义、
 * 宿主文件形状变了、或 `--require` 且找不到宿主检出）。
 */
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const DEFINITION = join(ROOT, 'conformance/typed-object-key.json')
const WRITER = 'yudao-module-ai1-springai/src/main/java/cn/iocoder/yudao/module/ai1/service/mcp/Ai1McpServiceImpl.java'
const CONSOLE = 'keelbase-ui/host-overlay/src/keelbase/sixQuestions.mjs'

const argv = process.argv.slice(2)
const arg = (name, dflt) => {
  const i = argv.indexOf(name)
  return i >= 0 ? argv[i + 1] : dflt
}
const STRICT = argv.includes('--strict')
const SELF_TEST = argv.includes('--self-test')
const REQUIRE = argv.includes('--require')
const DEFINITION_ONLY = argv.includes('--definition-only')
const HOST = resolve(ROOT, arg('--host', process.env.KEELBASE_HOST_REPO ?? '../KeelBase-Vue-Pro'))

const die = (message) => {
  console.error(`✗ ${message}`)
  process.exit(2)
}

if (!existsSync(DEFINITION)) die(`找不到 ${DEFINITION}`)
const definition = JSON.parse(readFileSync(DEFINITION, 'utf8'))
if (!Array.isArray(definition.kinds) || definition.kinds.length === 0) {
  die(`${DEFINITION} 的 kinds 不是非空数组`)
}
if (definition.shape !== ' · object <kind> #<id>' || definition.endAnchored !== true) {
  die(`${DEFINITION} 的 shape / endAnchored 与脚本认识的形状不同`)
}

/** 读宿主写入方里的种类常量（fail-closed：找不到即环境问题）。 */
const writerWord = (hostDir) => {
  const path = join(hostDir, WRITER)
  if (!existsSync(path)) die(`找不到宿主写入方 ${path}`)
  const m = readFileSync(path, 'utf8').match(/AUDIT_OBJECT_KIND\s*=\s*"([^"]+)"/)
  if (!m) die(`${path} 里解析不到 AUDIT_OBJECT_KIND —— 写入方的形状变了？`)
  return m[1]
}

/** 读控制台的词→类型映射（fail-closed：解析出 0 条即环境问题）。 */
const consoleKinds = (hostDir) => {
  const path = join(hostDir, CONSOLE)
  if (!existsSync(path)) die(`找不到控制台读取器 ${path}`)
  const src = readFileSync(path, 'utf8')
  const block = src.match(/export const OBJECT_KEY_KINDS = Object\.freeze\(\{([\s\S]*?)\}\)/)
  if (!block) die(`${path} 里解析不到 OBJECT_KEY_KINDS —— 映射表的形状变了？`)
  const pairs = [...block[1].matchAll(/(\w+)\s*:\s*'([^']*)'/g)].map((m) => [m[1], m[2]])
  if (pairs.length === 0) die(`${path} 的 OBJECT_KEY_KINDS 解析出 0 条 —— 映射表空了？`)
  return Object.fromEntries(pairs)
}

/** 跑一遍全部比对；返回 findings 列表（空数组 = 干净）。 */
const check = async (hostDir) => {
  const findings = []
  const word = writerWord(hostDir)
  const kinds = consoleKinds(hostDir)
  const consoleModule = await import(pathToFileURL(join(hostDir, CONSOLE)).href)

  for (const kind of definition.kinds) {
    if (word !== kind.word) {
      findings.push(
        `写入方 AUDIT_OBJECT_KIND = ${JSON.stringify(word)}，定义是 ${JSON.stringify(kind.word)}`,
      )
    }
    if (kinds[kind.resultType] !== kind.word) {
      findings.push(
        `控制台 OBJECT_KEY_KINDS[${kind.resultType}] = ${JSON.stringify(kinds[kind.resultType])}，`
          + `定义是 ${JSON.stringify(kind.word)}`,
      )
    }
    for (const [type, wordOfType] of Object.entries(kinds)) {
      if (!definition.kinds.some((k) => k.resultType === type && k.word === wordOfType)) {
        findings.push(`控制台认得的 ${type} → ${JSON.stringify(wordOfType)} 不在定义里`)
      }
    }
  }

  for (const row of definition.rows) {
    const got = consoleModule.recordedTypedObject({ detail: row.detail })
    const expected = row.resultType === null
      ? null
      : { resultType: row.resultType, resultId: row.resultId }
    const same = expected === null
      ? got === null
      : got !== null
        && got.resultType === expected.resultType
        && String(got.resultId) === String(expected.resultId)
    if (!same) {
      findings.push(
        `控制台对 [${row.name}] 读成 ${JSON.stringify(got)}，运行时是 ${JSON.stringify(expected)}`,
      )
    }
  }

  return findings
}

/**
 * Turn the console reader's end anchor from strict to tolerant, for the self-test's negative control.
 * 把控制台读取器的句末锚从严格改成宽容（`)$/` → `)\s*$/`），用于自检的负向对照。
 */
const tolerant = (src) =>
  src
    .split('\n')
    .map((line) => {
      if (!line.includes('.match(/ · object ')) return line
      return line.replace(/\\s*\$\//, '$/').replace(/\)\$\//, ')\\s*$/')
    })
    .join('\n')

/** 自检：干净副本应无发现；三种扰动各应产生至少一条发现。 */
const selfTest = async () => {
  const base = mkdtempSync(join(tmpdir(), 'object-key-contract-'))
  const clean = join(base, 'clean')
  const copyInto = (target, mutate = (s) => s) => {
    for (const rel of [WRITER, CONSOLE]) {
      const path = join(target, rel)
      mkdirSync(dirname(path), { recursive: true })
      writeFileSync(path, mutate(readFileSync(join(HOST, rel), 'utf8'), rel))
    }
  }

  try {
    copyInto(clean)
    const cleanFindings = await check(clean)
    if (cleanFindings.length !== 0) {
      console.error('✗ 自检：未扰动的副本应当无发现，实际：')
      for (const finding of cleanFindings) console.error(`  · ${finding}`)
      return false
    }

    const controls = [
      ['写入方改词', (src, rel) => (rel === WRITER ? src.replace(/AUDIT_OBJECT_KIND\s*=\s*"[^"]+"/, 'AUDIT_OBJECT_KIND = "MCP Connection"') : src)],
      ['控制台收录未知种类', (src, rel) => (rel === CONSOLE ? src.replace("Object.freeze({ ai1_mcp: 'MCP connection' })", "Object.freeze({ ai1_mcp: 'MCP connection', other: 'Other' })") : src)],
      // 无论副本原本严格还是宽容，这一步都把它改成**宽容**（去掉已有的 `\s*` 再加回来）。
      ['控制台容忍尾随空白', (src, rel) => (rel === CONSOLE ? tolerant(src) : src)],
    ]
    for (const [name, mutate] of controls) {
      const dir = join(base, name.replace(/[^a-zA-Z一-龥]/g, '_'))
      copyInto(dir, mutate)
      const findings = await check(dir).catch((error) => [String(error)])
      if (findings.length === 0) {
        console.error(`✗ 自检：扰动「${name}」没有产生发现 —— 这条检查是空转的`)
        return false
      }
    }
    return true
  } finally {
    rmSync(base, { recursive: true, force: true })
  }
}

if (SELF_TEST) {
  if (!existsSync(HOST)) die(`自检需要宿主检出：${HOST}（--host 或 KEELBASE_HOST_REPO）`)
  const ok = await selfTest()
  if (!ok) process.exit(1)
  console.log('✓ 自检：干净副本无发现，三种扰动均被拦下')
  process.exit(0)
}

if (DEFINITION_ONLY || !existsSync(join(HOST, CONSOLE))) {
  if (!DEFINITION_ONLY && REQUIRE) {
    console.error(`✗ 没有宿主检出：${HOST}（--require）`)
    process.exit(2)
  }
  const why = DEFINITION_ONLY ? '按 --definition-only，不比对宿主' : `没有宿主检出（${HOST}）`
  console.log(`· ${why}；只核对定义本身：${definition.kinds.length} 个种类、`
    + `${definition.pairs.length} 对拼写、${definition.rows.length} 行读回样例`)
  process.exit(0)
}

const findings = await check(HOST)
for (const finding of findings) console.log(`✗ ${finding}`)
if (findings.length === 0) {
  console.log(`✓ 宿主写入方、控制台映射与读回，全部与 ${definition.kinds.length} 个定义种类一致`
    + `（${definition.rows.length} 行样例）`)
} else {
  console.log(`✗ ${findings.length} 处与定义不一致（宿主：${HOST}）`)
}
process.exit(findings.length === 0 || !STRICT ? 0 : 1)
