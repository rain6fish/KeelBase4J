// SPDX-License-Identifier: Apache-2.0
//
// Does the runtime-neutral console really sign in against a generated application?
//
// It drives a real browser through the console's own login form — typing a username and a passphrase,
// pressing the button, and reading where it lands — because nothing below the browser can answer this.
// The demo script's curl assertions talk to the API directly and bypass the built bundle entirely,
// which is exactly how a defect that broke the browser went unnoticed: with Git Bash rewriting
// `VITE_API_BASE=/api/v1` into a Windows path, the console called a file path, the browser issued no
// request at all, and every curl check stayed green. Nothing short of a browser could see that, so
// this is the check that can (JV-24 acceptance line 5: every step judged by a reading or an exit code).
//
//   Prerequisites: a running demo stack
//                  (`bash scripts/demo-flagship-generated.sh --ui`, which prints the passphrase),
//                  Node >= 22 (it has a global `WebSocket`), and Chrome or Edge.
//
//   CONSOLE_URL  the console's entry          (default http://localhost:18087/admin/)
//   DEMO_PASS    the injected passphrase      (required — the script refuses to guess one)
//   CONSOLE_USERS  user:role:expected-hash    (default alice:user:#/workbench,carol:admin:#/)
//   CHROME       the browser binary           (default: probed at the usual places)
//
//   node scripts/check-console-login.mjs      # exit 0 = the console signs in; exit 1 = it does not
//
// 控制台真的能登进一个生成物吗？
//
// 它驱动**真实浏览器**走控制台**自己的登录表单**——敲用户名与口令、按按钮、读它落到哪——因为浏览器之下
// 什么都回答不了这个问题。演示脚本的那些 curl 断言直接打接口、**完全绕过产物**，而这正是那个坏到浏览器里
// 的缺陷没被发现的原因：Git Bash 把 `VITE_API_BASE=/api/v1` 改写成 Windows 路径后，控制台去调一个文件
// 路径、浏览器**一条请求都不发**，而每条 curl 检查照旧全绿。只有浏览器看得见，所以这条检查才存在
// （JV-24 验收线 5：每步都有读数或退出码可判）。
//
//   前置：一套跑着的演示栈（`bash scripts/demo-flagship-generated.sh --ui`，它会打印口令）、
//         Node ≥ 22（有全局 `WebSocket`）、以及 Chrome 或 Edge。
//
//   CONSOLE_URL  控制台入口（默认 http://localhost:18087/admin/）
//   DEMO_PASS    注入的口令（**必填**——脚本拒绝去猜一个）
//   CONSOLE_USERS  用户:角色:期望的 hash（默认 alice:user:#/workbench,carol:admin:#/）
//   CHROME       浏览器可执行文件（默认：在常见位置探测）
//
//   node scripts/check-console-login.mjs      # 退出 0 = 控制台能登进去；退出 1 = 不能
import { spawn } from 'node:child_process'
import { existsSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CONSOLE_URL = process.env.CONSOLE_URL || 'http://localhost:18087/admin/'
const PASS = process.env.DEMO_PASS
const USERS = (process.env.CONSOLE_USERS || 'alice:user:#/workbench,carol:admin:#/').split(',')
const DEBUG_PORT = Number(process.env.CDP_PORT || 9223)

// Used only before the browser exists: `process.exit` skips `finally`, so anything raised *after* the
// launch has to go through the catch instead — otherwise a failure leaves an orphaned browser and its
// temporary profile behind.
//
// 只在浏览器还不存在时用：`process.exit` 会跳过 `finally`，故启动**之后**的错误必须走 catch——否则一次
// 失败会留下一个孤儿浏览器和它的临时 profile。
const die = (message, hint) => {
  console.error(`FAIL ${message}`)
  if (hint) console.error(`     ${hint}`)
  process.exit(1)
}

// A guard rather than a crash later: without it the failure is a stack trace about WebSocket, which
// says nothing about what to do.
//
// 先挡一道，而不是等它崩：否则失败会是一条关于 WebSocket 的堆栈，读不出该怎么办。
if (typeof WebSocket !== 'function') {
  die(`Node ${process.version} has no global WebSocket`,
    'this check needs Node >= 22; upgrade, or run it with a newer Node.')
}
if (!PASS) {
  die('DEMO_PASS is not set',
    'the passphrase the demo injected — the demo script prints it; export DEMO_PASS=<it> and re-run.')
}

const CHROME_CANDIDATES = [
  process.env.CHROME,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
].filter(Boolean)
const CHROME = CHROME_CANDIDATES.find((p) => existsSync(p))
if (!CHROME) {
  die('no Chrome or Edge found',
    `set CHROME=<path to the browser binary>; probed: ${CHROME_CANDIDATES.join(', ')}`)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const profile = mkdtempSync(join(tmpdir(), 'keelbase-console-login-'))
const browser = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=${DEBUG_PORT}`, `--user-data-dir=${profile}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu',
  // A system proxy would swallow the same-origin calls this check is about, and the console reports
  // the result as "Network error" — an application-shaped message for an environment problem.
  //
  // 系统代理会吞掉这条检查要看的那几个同源调用，而控制台把结果报成「Network error」——一个应用形状的
  // 说法，说的却是环境问题。
  '--no-proxy-server', '--proxy-bypass-list=<-loopback>',
  'about:blank',
], { stdio: 'ignore' })

let failures = 0
const check = (name, passed, detail = '') => {
  if (passed) console.log(`  ok   ${name}`)
  else { console.log(`  FAIL ${name}${detail ? ` -- ${detail}` : ''}`); failures += 1 }
}

async function pageTarget() {
  for (let i = 0; i < 60; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/list`)).json()
      const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl)
      if (page) return page
    } catch { /* the browser is still starting */ }
    await sleep(500)
  }
  throw new Error(`the browser never exposed a page to drive — launched: ${CHROME}`)
}

function connect(url) {
  const ws = new WebSocket(url)
  let id = 0
  const pending = new Map()
  const requests = []
  ws.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (message.id && pending.has(message.id)) { pending.get(message.id)(message); pending.delete(message.id) }
    else if (message.method === 'Network.requestWillBeSent') requests.push(message.params.request.url)
  })
  const ready = new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve)
    ws.addEventListener('error', (e) => reject(new Error(`websocket: ${e.message}`)))
  })
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const mid = ++id
    pending.set(mid, (m) => (m.error ? reject(new Error(`${method}: ${JSON.stringify(m.error)}`)) : resolve(m.result)))
    ws.send(JSON.stringify({ id: mid, method, params }))
  })
  return { ready, send, requests, close: () => ws.close() }
}

async function main() {
  const target = await pageTarget()
  const cdp = connect(target.webSocketDebuggerUrl)
  await cdp.ready
  await cdp.send('Page.enable')
  await cdp.send('Runtime.enable')
  await cdp.send('Network.enable')

  const evaluate = async (expression) => {
    const r = await cdp.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
    if (r.exceptionDetails) throw new Error(`evaluating in the page threw: ${r.exceptionDetails.exception?.description}`)
    return r.result.value
  }
  const until = async (expression, tries = 60) => {
    for (let i = 0; i < tries; i++) {
      if (await evaluate(expression)) return true
      await sleep(500)
    }
    return false
  }
  const onLoginForm = () => "document.querySelectorAll('.el-input__inner').length >= 2"
  const loadFresh = async () => {
    await cdp.send('Page.navigate', { url: CONSOLE_URL })
    // A previous identity's session would sign in by itself and leave no form to fill. Best-effort on
    // purpose: on an error page (nothing served at CONSOLE_URL) even reading `localStorage` is denied,
    // and letting that throw would report a browser security error where the honest answer is "the
    // demo is not up" — which is what the caller below says.
    //
    // 上一个身份的会话会自己登进去，就没有表单可填了。**故意**做成尽力而为：在错误页上（CONSOLE_URL
    // 什么都没伺服）连读 `localStorage` 都会被拒，让它抛出来会把「浏览器安全错误」当成结论，而诚实的
    // 答案是「演示没起」——那正是下面调用处会说的话。
    await sleep(1500)
    await evaluate("(() => { try { localStorage.clear() } catch { /* an error page has none */ } return true })()")
      .catch(() => {})
    await cdp.send('Page.navigate', { url: CONSOLE_URL })
    return until(onLoginForm())
  }

  for (const spec of USERS) {
    const [who, role, home] = spec.split(':')
    console.log(`== ${who} (role ${role}, home ${home}) ==`)
    cdp.requests.length = 0
    if (!(await loadFresh())) {
      throw new Error('the console never rendered its login form — is the demo up at ' + CONSOLE_URL +
        '? (bash scripts/demo-flagship-generated.sh --ui prints the passphrase and serves it). ' +
        'If it is up, the console may be calling a base that is not a URL — see the guard on that build line.')
    }
    check('the console renders its login form', true)

    await evaluate(`(() => {
      const inputs = document.querySelectorAll('.el-input__inner')
      const set = (el, value) => { el.value = value; el.dispatchEvent(new Event('input', { bubbles: true })) }
      set(inputs[0], ${JSON.stringify(who)})
      set(inputs[1], ${JSON.stringify(PASS)})
      return true
    })()`)
    await evaluate("document.querySelector('button[type=submit]').click()")

    const left = await until("!location.hash.includes('login')", 80)
    const hash = await evaluate('location.hash')
    check('signing in leaves the login page', left, `still at ${hash}`)
    check(`and lands on the ${role} home (${home})`, hash === home, `landed at ${hash}`)
    check('the console restored its session through /auth/me',
      cdp.requests.some((u) => u.includes('/auth/me')))
    check('and loaded capabilities through /auth/me/permissions',
      cdp.requests.some((u) => u.includes('/auth/me/permissions')))

    const shell = await evaluate('document.body.innerText')
    check('the shell renders the signed-in identity', shell.includes(who), shell.slice(0, 120).replace(/\n/g, ' | '))
  }

  console.log('== a wrong passphrase ==')
  await loadFresh()
  await evaluate(`(() => {
    const inputs = document.querySelectorAll('.el-input__inner')
    const set = (el, value) => { el.value = value; el.dispatchEvent(new Event('input', { bubbles: true })) }
    set(inputs[0], ${JSON.stringify(USERS[0].split(':')[0])})
    set(inputs[1], 'not-the-passphrase')
    return true
  })()`)
  await evaluate("document.querySelector('button[type=submit]').click()")
  await sleep(3000)
  check('a wrong passphrase stays on the login page', await evaluate("location.hash.includes('login')"),
    `landed at ${await evaluate('location.hash')}`)

  cdp.close()
}

const waitForExit = (child, ms) => new Promise((resolve) => {
  if (child.exitCode !== null || child.signalCode) return resolve(true)
  const timer = setTimeout(() => resolve(false), ms)
  child.once('exit', () => { clearTimeout(timer); resolve(true) })
})

try {
  await main()
} catch (error) {
  console.error(`FAIL ${error.message}`)
  failures += 1
} finally {
  browser.kill()
  // Wait for it to actually be gone: `kill` returns immediately, and on Windows the profile stays
  // locked until the process (and its helpers) have exited — removing it first throws EPERM, which
  // would report a passing check as a failure.
  //
  // 等它真的没了：`kill` 是立即返回的，而 Windows 上 profile 会一直锁着，直到进程（及其辅助进程）退出
  // ——这时去删会抛 EPERM，把一次通过的检查报成失败。
  await waitForExit(browser, 5000)
  try {
    rmSync(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 300 })
  } catch (error) {
    console.error(`note: left the temporary browser profile at ${profile} (${error.code}) — ` +
      'not a failure of the check')
  }
}

console.log(failures === 0
  ? 'PASS — the console signs in through its own form, and refuses a wrong passphrase'
  : `FAIL — ${failures} assertion(s) did not hold`)
process.exit(failures === 0 ? 0 : 1)
