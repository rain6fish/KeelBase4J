#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0

/**
 * The typed object key is a spelling shared by three code paths in two repositories: the runtime's
 * sentence matcher, the host's MCP connect-test writer, and the console's reader. Nothing in the type
 * system ties them together — the key lives in free-text `detail` — so a rename on either side would
 * leave every test green while the read face silently answered "no match". This gate reads the three
 * spellings and fails when they disagree.
 *
 * It reads source files, not a running deployment: the runtime's `typedObjectKeySuffix`, the host's
 * `AUDIT_OBJECT_KIND` and append line, and the console's `OBJECT_KEY_KINDS` plus its end-anchored
 * regex. The host checkout is resolved from `KEELBASE_VUEPRO_DIR`, then from the main checkout's
 * sibling `KeelBase-Vue-Pro`, then from this repository's sibling. When it is absent the gate says
 * `SKIP` and exits 0 — a machine without the host must not fail a runtime gate — unless `--require`
 * is passed, which turns the absence into a failure for the caller that knows the host should be there.
 *
 * `--self-test` builds three tiny fixtures and mutates each shared literal in turn; every mutation must
 * make the gate fail. That is the part that proves the gate catches drift rather than merely running.
 *
 * 行末带类型键的拼写由**两仓三处**共享：运行时的句末匹配器、宿主的 MCP 连通测试写入方、控制台的读回。
 * 类型系统管不到它们 —— 键在自由文本 `detail` 里 —— 任一侧改名，测试会全绿而读面静默答「无匹配」。
 * 本闸读这三处拼写，不一致即失败。
 *
 * 它读源码、不起部署：运行时的 `typedObjectKeySuffix`、宿主的 `AUDIT_OBJECT_KIND` 与追加行、控制台的
 * `OBJECT_KEY_KINDS` 与句末正则。宿主检出先取 `KEELBASE_VUEPRO_DIR`，再取主检出旁的
 * `KeelBase-Vue-Pro`，最后取本仓旁的同名目录。找不到时打印 `SKIP` 并退 0 —— 没有宿主检出的机器不应
 * 让运行时的门禁变红 —— 除非传了 `--require`，那会把「找不到」变成失败，留给确知宿主应在的调用方。
 *
 * `--self-test` 造三个极小的夹具，逐个改动共享字面量；每一次改动都必须让闸失败。这部分证明的是闸
 * **抓得住漂移**，而不只是跑得起来。
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const RUNTIME_FILE =
  'keelbase4j-core/src/main/java/cn/com/keelbase/runtime/audit/AuditService.java';
const HOST_FILE =
  'yudao-module-ai1-springai/src/main/java/cn/iocoder/yudao/module/ai1/service/mcp/Ai1McpServiceImpl.java';
const CONSOLE_FILE = 'keelbase-ui/host-overlay/src/keelbase/sixQuestions.mjs';

function read(root, relative) {
  return fs.readFileSync(path.join(root, relative), 'utf8').replace(/\r\n/g, '\n');
}

/** The runtime's suffix construction. / 运行时那一侧的后缀构造。 */
function runtimeSpelling(root) {
  const m = read(root, RUNTIME_FILE).match(/return "([^"]*)" \+ word \+ "([^"]*)" \+ key;/);
  if (!m) {
    throw new Error(
      `${RUNTIME_FILE}: 找不到 \`return "<prefix>" + word + "<suffix>" + key;\` / construction not found`,
    );
  }
  return { prefix: m[1], suffix: m[2] };
}

/** The host writer's kind word and append line. / 宿主写入方的种类词与追加行。 */
function hostSpelling(host) {
  const text = read(host, HOST_FILE);
  const word = text.match(/AUDIT_OBJECT_KIND\s*=\s*"([^"]*)"/);
  const append = text.match(
    /\+ "([^"]*)" \+ AUDIT_OBJECT_KIND \+ "([^"]*)" \+ mcp\.getId\(\)/,
  );
  if (!word || !append) {
    throw new Error(
      `${HOST_FILE}: 找不到 AUDIT_OBJECT_KIND 或 \`+ "<prefix>" + AUDIT_OBJECT_KIND + "<suffix>" + mcp.getId()\``,
    );
  }
  return { word: word[1], prefix: append[1], suffix: append[2] };
}

/** The console's word map and end-anchored regex. / 控制台的词表与句末正则。 */
function consoleSpelling(host) {
  const text = read(host, CONSOLE_FILE);
  const map = text.match(
    /OBJECT_KEY_KINDS\s*=\s*Object\.freeze\(\{\s*ai1_mcp:\s*'([^']*)'\s*\}\)/,
  );
  // The file has more than one `.match(/…/)` reader (the blocked-call key is the other). Pick the
  // one whose pattern carries the typed ` · object ` prefix; taking the first `const hit` would pin
  // the wrong spelling.
  // 文件里不止一个 `.match(/…/)` 读法（另一条是被拦下调用那个键）；按「模式里带 ` · object `」挑，
  // 取第一个 `const hit` 会钉错拼写。
  const source = [...text.matchAll(/\.match\(\/(.+?)\/\)/g)]
    .map((m) => m[1])
    .find((pattern) => pattern.includes(' · object '));
  if (!map || !source) {
    throw new Error(`${CONSOLE_FILE}: 找不到 OBJECT_KEY_KINDS 或带类型键的句末正则 / map or regex not found`);
  }
  // The quantifier after `\d` is not part of the spelling: the console may tighten `\d+` to
  // `\d{1,18}` to match the runtime's cap without changing a single separator. Pin the prefix,
  // suffix and word; leave the digit rule to the two implementations' own tests.
  // `\d` 后面的量词不属于拼写：控制台可以把 `\d+` 收紧成 `\d{1,18}` 去对齐运行时的上限，而不动任何
  // 分隔符。闸钉前缀、后缀与词；位数规则留给两侧各自的测试。
  const parts = source.match(/^([\s\S]*?)\(\.\+\?\)([\s\S]*?)\(\\d[^)]*\)/);
  if (!parts) {
    throw new Error(`${CONSOLE_FILE}: 句末正则不是 \`<prefix>(.+?)<suffix>(\\d+)\` 形状：${source}`);
  }
  return { word: map[1], prefix: parts[1], suffix: parts[2], source };
}

/** Every disagreement, or an empty list when the three spellings agree. / 三处拼写的全部不一致；一致时为空。 */
function check(runtimeRoot, hostRoot) {
  const runtime = runtimeSpelling(runtimeRoot);
  const host = hostSpelling(hostRoot);
  const console = consoleSpelling(hostRoot);
  const failures = [];

  if (!runtime.prefix || !runtime.suffix) {
    failures.push(`runtime: 前缀或后缀为空 / empty prefix or suffix (${JSON.stringify(runtime)})`);
  }
  if (!host.word) failures.push('host: 种类词为空 / empty kind word');
  if (runtime.prefix !== host.prefix) {
    failures.push(
      `prefix 不一致 / prefix differs: runtime=${JSON.stringify(runtime.prefix)} ` +
        `host=${JSON.stringify(host.prefix)}`,
    );
  }
  if (runtime.suffix !== host.suffix) {
    failures.push(
      `suffix 不一致 / suffix differs: runtime=${JSON.stringify(runtime.suffix)} ` +
        `host=${JSON.stringify(host.suffix)}`,
    );
  }
  if (host.word !== console.word) {
    failures.push(
      `种类词不一致 / kind word differs: host=${JSON.stringify(host.word)} ` +
        `console=${JSON.stringify(console.word)}`,
    );
  }
  if (console.prefix !== runtime.prefix) {
    failures.push(
      `console prefix 不一致 / differs: runtime=${JSON.stringify(runtime.prefix)} ` +
        `console=${JSON.stringify(console.prefix)}`,
    );
  }
  if (console.suffix !== runtime.suffix) {
    failures.push(
      `console suffix 不一致 / differs: runtime=${JSON.stringify(runtime.suffix)} ` +
        `console=${JSON.stringify(console.suffix)}`,
    );
  }
  return { failures, runtime, host, console };
}

function resolveHost(explicit) {
  if (explicit) return path.resolve(explicit);
  if (process.env.KEELBASE_VUEPRO_DIR) return path.resolve(process.env.KEELBASE_VUEPRO_DIR);
  try {
    const common = execFileSync(
      'git',
      ['-C', ROOT, 'rev-parse', '--path-format=absolute', '--git-common-dir'],
      { encoding: 'utf8' },
    ).trim();
    const mainRoot = path.resolve(common, '..');
    const sibling = path.resolve(mainRoot, '..', 'KeelBase-Vue-Pro');
    if (fs.existsSync(sibling)) return sibling;
  } catch {
    // Not a git checkout, or an old git without --path-format: fall through to the plain sibling.
    // 不是 git 检出，或 git 太旧没有 --path-format：退到普通兄弟目录。
  }
  return path.resolve(ROOT, '..', 'KeelBase-Vue-Pro');
}

function parseArgs(argv) {
  const options = { selfTest: false, require: false, host: null, runtime: ROOT };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--self-test') options.selfTest = true;
    else if (arg === '--require') options.require = true;
    else if (arg === '--host') options.host = argv[++i];
    else if (arg === '--runtime') options.runtime = path.resolve(argv[++i]);
    else {
      console.error(`unknown argument: ${arg}`);
      process.exit(2);
    }
  }
  return options;
}

function writeFixture(root, relative, body) {
  const full = path.join(root, relative);
  fs.mkdirSync(path.dirname(full), { recursive: true });
  fs.writeFileSync(full, body, 'utf8');
  return full;
}

function selfTest() {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'keelbase-host-object-key-'));
  const runtimeRoot = path.join(temp, 'runtime');
  const hostRoot = path.join(temp, 'host');
  const runtimeFixture = [
    'class AuditService {',
    '    static String typedObjectKeySuffix(String kind, String key) {',
    '        String word = kind.trim();',
    '        return " · object " + word + " #" + key;',
    '    }',
    '}',
    '',
  ].join('\n');
  const hostFixture = [
    'class Ai1McpServiceImpl {',
    '    static final String AUDIT_OBJECT_KIND = "MCP connection";',
    '    void row() {',
    '        String detail = "mcp test"',
    '                    + " · object " + AUDIT_OBJECT_KIND + " #" + mcp.getId();',
    '    }',
    '}',
    '',
  ].join('\n');
  const consoleFixture = [
    "export const OBJECT_KEY_KINDS = Object.freeze({ ai1_mcp: 'MCP connection' })",
    'export function recordedTypedObject(row) {',
    "  const hit = String(row?.detail ?? '').match(/ · object (.+?) #(\\d+)\\s*$/)",
    '  return hit',
    '}',
    '',
  ].join('\n');

  const reset = () => {
    writeFixture(runtimeRoot, RUNTIME_FILE, runtimeFixture);
    writeFixture(hostRoot, HOST_FILE, hostFixture);
    writeFixture(hostRoot, CONSOLE_FILE, consoleFixture);
  };

  const cases = [
    ['host kind word', HOST_FILE, 'AUDIT_OBJECT_KIND = "MCP connection"', 'AUDIT_OBJECT_KIND = "MCP Connection"'],
    ['host prefix', HOST_FILE, '+ " · object " + AUDIT_OBJECT_KIND', '+ " · Object " + AUDIT_OBJECT_KIND'],
    ['console kind word', CONSOLE_FILE, "ai1_mcp: 'MCP connection'", "ai1_mcp: 'MCP link'"],
    ['console regex', CONSOLE_FILE, '/ · object (.+?) #(\\d+)\\s*$/', '/ · object  (.+?) #(\\d+)\\s*$/'],
    ['runtime suffix', RUNTIME_FILE, 'return " · object " + word + " #" + key;', 'return " · object " + word + " # " + key;'],
  ];

  try {
    reset();
    const base = check(runtimeRoot, hostRoot);
    if (base.failures.length > 0) {
      console.error('SELF-TEST FAIL: the unmodified fixtures do not agree');
      for (const failure of base.failures) console.error(`  - ${failure}`);
      process.exit(1);
    }

    let caught = 0;
    for (const [name, relative, from, to] of cases) {
      reset();
      const file = path.join(relative === RUNTIME_FILE ? runtimeRoot : hostRoot, relative);
      const before = fs.readFileSync(file, 'utf8');
      if (!before.includes(from)) {
        console.error(`SELF-TEST FAIL: fixture does not contain the literal for "${name}"`);
        process.exit(1);
      }
      fs.writeFileSync(file, before.replace(from, to), 'utf8');
      if (check(runtimeRoot, hostRoot).failures.length === 0) {
        console.error(`SELF-TEST FAIL: a changed ${name} was not caught`);
        process.exit(1);
      }
      caught += 1;
    }
    console.log(`SELF-TEST PASS: ${caught}/${cases.length} drifts caught`);
  } finally {
    fs.rmSync(temp, { recursive: true, force: true });
  }
}

const options = parseArgs(process.argv.slice(2));
if (options.selfTest) {
  selfTest();
  process.exit(0);
}

const hostRoot = resolveHost(options.host);
if (!fs.existsSync(path.join(options.runtime, RUNTIME_FILE))) {
  console.error(`FAIL: runtime file not found: ${path.join(options.runtime, RUNTIME_FILE)}`);
  process.exit(1);
}
for (const relative of [HOST_FILE, CONSOLE_FILE]) {
  if (!fs.existsSync(path.join(hostRoot, relative))) {
    const message =
      `host checkout not found at ${hostRoot} (missing ${relative}); ` +
      'set KEELBASE_VUEPRO_DIR or pass --host';
    if (options.require) {
      console.error(`FAIL: ${message}`);
      process.exit(1);
    }
    console.log(`SKIP: ${message}`);
    process.exit(0);
  }
}

let result;
try {
  result = check(options.runtime, hostRoot);
} catch (error) {
  console.error(`FAIL: ${error.message}`);
  process.exit(1);
}
if (result.failures.length > 0) {
  console.error('FAIL: typed object key spelling drifts across repositories');
  for (const failure of result.failures) console.error(`  - ${failure}`);
  process.exit(1);
}
console.log(
  `PASS: typed object key spelling agrees — prefix=${JSON.stringify(result.runtime.prefix)} ` +
    `suffix=${JSON.stringify(result.runtime.suffix)} word=${JSON.stringify(result.host.word)} ` +
    `(runtime ↔ host writer ↔ console)`,
);
