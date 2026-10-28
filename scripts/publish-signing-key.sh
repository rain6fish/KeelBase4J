#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Publish the release signing key to a keyserver Central reads — with a *bounded* failure.
#
# Where this sits in the release chain: the key signs every artifact the deploy step uploads, and Central
# refuses a deployment whose signatures it cannot check ("Could not find a public key by the key
# fingerprint"). So the public half has to be on a keyserver Central queries *and indexed there*, before
# the deploy runs. Sending is idempotent; repeating it is harmless.
#
# Why this is a script and not two lines in the workflow: on 2026-10-26 (v0.1.13) `--keyserver
# keyserver.ubuntu.com` got no answer at all, `gpg` waited forever, and the job sat there until GitHub's
# six-hour cap cancelled it — half a day lost, and Central received nothing. That is the failure this
# bounds. Measured on gpg 2.4.9 while writing this: `--keyserver-options connect-timeout=` is not a
# known option at all (gpg warns and carries on), and `--keyserver-options timeout=8` against an
# unreachable host still took 61s — so dirmngr's own knobs are not a bound one can rely on. The bound
# here is an outer `timeout(1)` per attempt: it is the only one that held when measured. dirmngr does
# the socket work in a daemon of its own, so it is killed between attempts as well; otherwise a stuck
# connection survives the killed client and the next attempt queues behind it.
#
# What a failure looks like now: at most SERVERS × ATTEMPTS attempts of ATTEMPT_TIMEOUT seconds each
# (with backoff), then either a warning that the key is already published, or a non-zero exit naming the
# servers tried and what to do next — inside a step that is itself capped by `timeout-minutes`.
#
# Environment (defaults are what the release workflow uses):
#   GPG_KEY_ID              — the signing key, as a key id or fingerprint
#   KEYSERVERS              — space-separated, in the order they are tried
#   KEYSERVER_ATTEMPT_TIMEOUT — seconds per attempt (45)
#   KEYSERVER_ATTEMPTS      — attempts per server (2)
#   KEYSERVER_BACKOFF       — first backoff in seconds, doubled per retry (8)
#   GPG_BIN / GPGCONF_BIN   — overridable so `--self-test` can drive a fake gpg
#
#   bash scripts/publish-signing-key.sh <key-id>
#   bash scripts/publish-signing-key.sh --self-test    # proves the bounds without touching the network
#
# 把发布用的签名公钥发到 Central 会去查的 keyserver —— 而且失败是**有界**的。
#
# 这一步在发布链里的位置：签名公钥给 deploy 上传的每个 artifact 签名，而 Central 会拒绝一次「它验不了签名」
# 的部署（「Could not find a public key by the key fingerprint」）。所以公钥必须已经**在** Central 会去查的
# keyserver 上、并且**已经建好索引**，deploy 才跑。发送是幂等的，重复发送无害。
#
# 为什么写成脚本而不是 workflow 里的两行：2026-10-26（v0.1.13）`--keyserver keyserver.ubuntu.com` 一点回应
# 都没有，`gpg` 就一直等，作业挂到 GitHub 六小时上限被取消——半天没了，Central 一个构件都没收到。这里给这种
# 失败加上界。写这份脚本时在 gpg 2.4.9 上实测：`--keyserver-options connect-timeout=` 根本不是一个已知选项
# （gpg 警告一句就继续），而对一个不可达的主机用 `--keyserver-options timeout=8`，整体仍然花了 61 秒——所以
# dirmngr 自己的旋钮靠不住。这里真正管用的界是**每次尝试外面套的 `timeout(1)`**：实测里只有它守住了。dirmngr
# 是在自己的守护进程里做套接字操作的，所以两次尝试之间把它杀掉；否则那条卡住的连接会活过被杀的客户端，下一次
# 尝试就排在它后面继续等。
#
# 现在失败长什么样：最多 SERVERS × ATTEMPTS 次、每次 ATTEMPT_TIMEOUT 秒（带退避），然后要么警告「公钥本来就
# 已发布」，要么非零退出并点名试过哪些服务器、下一步该做什么——而这一步本身还被 `timeout-minutes` 封着。
#
# 环境变量（默认值即发布流水线用的那套）：
#   GPG_KEY_ID              — 签名密钥，key id 或指纹
#   KEYSERVERS              — 空格分隔，按顺序尝试
#   KEYSERVER_ATTEMPT_TIMEOUT — 每次尝试的秒数（45）
#   KEYSERVER_ATTEMPTS      — 每台服务器试几次（2）
#   KEYSERVER_BACKOFF       — 首次退避秒数，之后每次翻倍（8）
#   GPG_BIN / GPGCONF_BIN   — 可覆盖，`--self-test` 用它驱动一个假 gpg
#
#   bash scripts/publish-signing-key.sh <key-id>
#   bash scripts/publish-signing-key.sh --self-test    # 不碰网络，证明这些界确实生效
set -uo pipefail

GPG_BIN=${GPG_BIN:-gpg}
GPGCONF_BIN=${GPGCONF_BIN:-gpgconf}
# Both are keyservers Central documents as supported. `pgp.mit.edu`, the third one the docs name, is the
# one the old step fell back to with `|| true` — and it did not answer a probe within 20s, so that
# fallback was decoration rather than a second path.
#
# 这两台都是 Central 文档里列为支持的 keyserver。文档点名的第三台 `pgp.mit.edu` 正是旧步骤拿 `|| true`
# 兜底的那台——而它 20 秒内都答不上一次探测，所以那条兜底只是摆设，不是第二条路。
KEYSERVERS=${KEYSERVERS:-"hkp://keyserver.ubuntu.com hkps://keys.openpgp.org"}
ATTEMPT_TIMEOUT=${KEYSERVER_ATTEMPT_TIMEOUT:-45}
ATTEMPTS=${KEYSERVER_ATTEMPTS:-2}
BACKOFF=${KEYSERVER_BACKOFF:-8}
# The grace `timeout` grants after the deadline before it stops asking and kills. It is a knob because the
# self-test's time caps are derived from it rather than hand-tuned: a hung child costs `ATTEMPT_TIMEOUT +
# KILL_AFTER`, and on a host that cannot deliver SIGTERM into a child process (Cygwin / Git Bash) that
# grace is always paid in full, so caps written for `ATTEMPT_TIMEOUT` alone fail there.
#
# `timeout` 在到期之后、真正下杀手之前给的宽限。做成可调项，是因为自检的时间上限由它推出、而不是手调：
# 一个挂住的孩子最坏要花 `ATTEMPT_TIMEOUT + KILL_AFTER`，而在无法把 SIGTERM 投递进子进程的主机上
# （Cygwin / Git Bash），这份宽限每次都要付满 —— 只按 `ATTEMPT_TIMEOUT` 写上限的断言在那里会红。
KILL_AFTER=${KEYSERVER_KILL_AFTER:-5}

say() { printf '%s\n' "$*"; }

# One bounded attempt of one gpg command. Returns gpg's status, or 124 when the wrapper had to kill it —
# keeping that distinct is the whole point, because "took too long" and "said no" need different advice.
#
# 一次有界的单次尝试。返回 gpg 的状态，或者外层不得不杀掉它时的 124 —— 把这两种分清是重点，因为「太慢」和
# 「明确拒绝」要对人说不同的话。
bounded_gpg() {
  timeout --kill-after="$KILL_AFTER" "$ATTEMPT_TIMEOUT" "$GPG_BIN" --batch --yes "$@"
}

# The I/O happens in dirmngr, not in the process `timeout` killed, so the next attempt starts from a
# clean daemon. Skipped silently where gpgconf is not next to gpg (the self-test's fake).
#
# I/O 发生在 dirmngr 里，不在被 `timeout` 杀掉的进程里，所以下一次尝试要从一个干净的守护进程开始。
# gpgconf 不在 gpg 旁边时（自检里的假 gpg）静默跳过。
kill_daemon() { "$GPGCONF_BIN" --kill dirmngr >/dev/null 2>&1 || true; }

# The full fingerprint, when the local keyring has the key: keys.openpgp.org only answers lookups by
# fingerprint, so a short key id would make the "is it already published?" check lie on one of the two.
#
# 本地钥匙环里有时取完整指纹：keys.openpgp.org 只按指纹回答查询，用短 key id 会让「是不是已经发布了」这个
# 判断在其中一台服务器上撒谎。
fingerprint() {
  "$GPG_BIN" --batch --with-colons --list-keys "$1" 2>/dev/null \
    | awk -F: '/^fpr:/{print $10; exit}'
}

is_published() {
  local id=$1 server tmp
  for server in $KEYSERVERS; do
    tmp=$(mktemp -d)
    if bounded_gpg --homedir "$tmp" --keyserver "$server" --recv-keys "$id" >/dev/null 2>&1; then
      rm -rf "$tmp"; return 0
    fi
    rm -rf "$tmp"
  done
  return 1
}

publish() {
  local key_id=$1 fpr server attempt rc wait
  fpr=$(fingerprint "$key_id")
  say "publishing ${fpr:-$key_id} to: $KEYSERVERS"

  for server in $KEYSERVERS; do
    wait=$BACKOFF
    for attempt in $(seq 1 "$ATTEMPTS"); do
      say "→ $server (attempt $attempt/$ATTEMPTS, ${ATTEMPT_TIMEOUT}s cap)"
      kill_daemon
      bounded_gpg --keyserver "$server" --send-keys "$key_id"
      rc=$?
      if [ "$rc" -eq 0 ]; then
        say "✓ published to $server"
        return 0
      fi
      if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then
        say "  no answer within ${ATTEMPT_TIMEOUT}s — gave up on this attempt"
      else
        say "  gpg refused (exit $rc)"
      fi
      [ "$attempt" -lt "$ATTEMPTS" ] && { say "  retrying in ${wait}s"; sleep "$wait"; wait=$((wait * 2)); }
    done
  done

  # A send that failed is not the same as a key that is not published: the key has been on these servers
  # since the first release that used it, and Central only needs to *find* it. So look before failing —
  # but only then, and only with a bounded lookup.
  #
  # 一次发送失败 ≠ 公钥没发布：从第一个用它的版本起，这把钥匙就一直在这些服务器上，而 Central 只需要**查得到**。
  # 所以先查一下再判失败——但只在此时、且这个查询同样有界。
  say "all sends failed; checking whether the key is already published"
  if is_published "${fpr:-$key_id}"; then
    say "⚠ the key is already retrievable — continuing, but re-run this workflow if Central rejects the deploy"
    return 0
  fi

  cat >&2 <<EOF

✗ Could not publish ${fpr:-$key_id} to any keyserver, and it is not retrievable from any of them:
    $KEYSERVERS
  Nothing was deployed; the job stops here on purpose rather than uploading signatures Central cannot check.

  What to do next:
    1. Re-run this workflow. Sending is idempotent, and the failure is usually the keyserver, not the key.
    2. If it fails again, check the servers by hand from your machine:
         gpg --keyserver hkp://keyserver.ubuntu.com --recv-keys ${fpr:-$key_id}
    3. Only if the key itself is in doubt: compare its fingerprint with the one in the repository's
       release notes — do not regenerate it, or every past artifact's signature stops verifying.

✗ 这台签名公钥发不到任何一台 keyserver，而且哪台都查不到它：
    $KEYSERVERS
  没有部署任何东西；作业在这里停下是有意的——与其上传一批 Central 验不了签名的构件，不如不发。

  下一步：
    1. 重跑本 workflow。发送是幂等的，失败通常是 keyserver 那边的事，不是钥匙。
    2. 还是失败就用本机手工查一下：
         gpg --keyserver hkp://keyserver.ubuntu.com --recv-keys ${fpr:-$key_id}
    3. 只有在怀疑钥匙本身时，才把它与仓库发布说明里的指纹比对——不要重新生成，否则过去所有构件的签名都验不过。
EOF
  return 1
}

# ---------------------------------------------------------------------------------------------------
# Self-test: a fake gpg that hangs or refuses on demand, so the bounds and the failover are shown to
# work instead of asserted. Each scenario prints its elapsed time and the servers actually tried.
#
# 自检：一个按需挂起或拒绝的假 gpg，用实际运行来展示「界」和「换服务器」真的生效，而不是断言它们生效。
# 每个场景都打印耗时和实际试过的服务器。
self_test() {
  local tmp failures=0
  tmp=$(mktemp -d)
  cat > "$tmp/fake-gpg" <<'FAKE'
#!/usr/bin/env bash
echo "gpg $*" >> "$FAKE_LOG"
server=""; cmd=""
for a in "$@"; do
  case "$a" in
    --send-keys) cmd=send ;;
    --recv-keys) cmd=recv ;;
    --list-keys) cmd=list ;;
    *://*) server=$a ;;
  esac
done
case "$cmd" in
  list) echo "fpr:::::::::0123456789ABCDEF0123456789ABCDEF01234567:" ;;
  recv) [ "${FAKE_RECV:-missing}" = found ] && exit 0
        echo "gpg: keyserver receive failed: No data" >&2; exit 2 ;;
  send)
    case " ${FAKE_HANG:-} " in *" $server "*) sleep 600 ;;
    esac
    case " ${FAKE_FAIL:-} " in *" $server "*) echo "gpg: keyserver send failed" >&2; exit 2 ;;
    esac
    echo "gpg: sending key to $server"; exit 0 ;;
esac
FAKE
  chmod +x "$tmp/fake-gpg"
  echo '#!/usr/bin/env bash' > "$tmp/fake-gpgconf"
  chmod +x "$tmp/fake-gpgconf"

  run() {  # run <label> <expected-exit> <max-seconds>
    local label=$1 want=$2 max=$3 start elapsed rc
    : > "$tmp/log"
    start=$(date +%s)
    GPG_BIN="$tmp/fake-gpg" GPGCONF_BIN="$tmp/fake-gpgconf" FAKE_LOG="$tmp/log" \
      GPG_KEY_ID=0123456789ABCDEF0123456789ABCDEF01234567 \
      KEYSERVERS="hkp://first.example hkp://second.example" \
      KEYSERVER_ATTEMPT_TIMEOUT=3 KEYSERVER_ATTEMPTS=2 KEYSERVER_BACKOFF=1 \
      bash "$0" > "$tmp/out" 2>&1
    rc=$?
    elapsed=$(( $(date +%s) - start ))
    if [ "$rc" -ne "$want" ]; then
      echo "  FAIL  $label: exit $rc, wanted $want"; sed 's/^/        /' "$tmp/out"; failures=$((failures + 1))
    elif [ "$elapsed" -gt "$max" ]; then
      echo "  FAIL  $label: took ${elapsed}s, wanted at most ${max}s"; failures=$((failures + 1))
    else
      echo "  ok    $label — exit $rc in ${elapsed}s (cap ${max}s)"
      grep -E '^(→|✓|⚠|✗)' "$tmp/out" | sed 's/^/        /'
    fi
  }

  # A hung attempt costs the child's 3s timeout *plus* KILL_AFTER, because the grace is paid in full where
  # SIGTERM does not reach the child (Cygwin / Git Bash). The caps are derived from that worst case rather
  # than hand-tuned to one platform, and the slack absorbs process-startup cost. What they guard is the
  # bound itself: an unbounded send overruns them by hours, not by seconds.
  #
  # 一次挂住的尝试要花子进程的 3 秒超时**加上** KILL_AFTER —— 在 SIGTERM 到不了子进程的地方（Cygwin /
  # Git Bash），那份宽限每次都要付满。上限由这个最坏情况推出，而不是按某一台机器手调；余量用来吸收进程
  # 启动开销。它们守的是**界本身**：去掉界之后超出的是小时级，不是秒级。
  local hung=$(( 3 + KILL_AFTER ))
  local cap_failover=$(( 2 * hung + 8 ))
  local cap_all_hung=$(( 4 * hung + 16 ))
  echo "self-test: 2 servers, 2 attempts each, 3s per attempt + ${KILL_AFTER}s kill grace, backoff 1s"
  FAKE_HANG="hkp://first.example" run "first server hangs, second answers → failover" 0 "$cap_failover"
  grep -q 'hkp://second.example' "$tmp/log" || { echo "  FAIL  the second server was never tried"; failures=$((failures + 1)); }
  FAKE_HANG="hkp://first.example hkp://second.example" run "both hang → bounded failure" 1 "$cap_all_hung"
  grep -q 'What to do next' "$tmp/out" || { echo "  FAIL  no guidance on failure"; failures=$((failures + 1)); }
  FAKE_HANG="hkp://first.example hkp://second.example" FAKE_RECV=found run "both hang but key already public → warn and continue" 0 "$cap_all_hung"
  grep -q 'already retrievable' "$tmp/out" || { echo "  FAIL  did not warn about an existing key"; failures=$((failures + 1)); }
  FAKE_FAIL="hkp://first.example" run "first server refuses, retry, then second answers" 0 "$cap_failover"
  [ "$(grep -c 'hkp://first.example' "$tmp/log")" -eq 2 ] \
    || { echo "  FAIL  the first server was not retried exactly twice"; failures=$((failures + 1)); }

  rm -rf "$tmp"
  [ "$failures" -eq 0 ] && echo "self-test: PASS" || echo "self-test: $failures failure(s)"
  return "$failures"
}

case "${1:-}" in
  --self-test) self_test; exit $? ;;
  -h|--help) sed -n '2,62p' "$0"; exit 0 ;;
esac

KEY_ID=${1:-${GPG_KEY_ID:-}}
[ -n "$KEY_ID" ] || { echo "usage: $0 <key-id>   (or set GPG_KEY_ID)" >&2; exit 2; }
publish "$KEY_ID"
