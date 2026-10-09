# CLAUDE.md — KeelBase4J

Java runtime for **KeelBase**, a conformance implementation of the KeelBase AI Governance Protocol.
The **machine-readable contract** — the frozen vectors, the wire schemas and the registry that indexes
them — lives in the **contract repository** (`rain6fish/keelbase-contract`); the **prose protocol**
stays in `docs/protocols/ai-governance-protocol.md` in the main repository (`rain6fish/KeelBase`).
This repository only **consumes** both.

## 硬规则（红线，Day 1 钉死）

1. **实现冻结契约，不翻译参照实现。** 协议是唯一真源，不是 TypeScript 代码。兼容性由复现
   语言无关向量证明——同一批文件，任何其他实现都该用。
2. **`conformance/vectors/` 是只读快照，禁止手改。** 刷新一律跑 `scripts/sync-vectors.sh`。
   协议变更**先在契约仓**落地（向量 → 实现），本仓只消费；快照与上游的漂移由 CI 的 `vector-drift` 拦。
3. **生成物 = 真实、可独立运行的 Java/Spring 源码。** 不是运行期读 JSON 解释的低代码：
   文件是能打开、读懂、手改的 `.java`；生成器退场后应用自持。
4. **产物基线 Java 17**（`--release 17`）。
5. **`keelbase4j-protocol` 零第三方依赖**（JDK only）；JUnit 仅 test scope。它正是生成物依赖的
   那个 artifact——往它上面加依赖，等于加到每一套生成应用上。runtime / generator 用 Spring 不受此限。
6. **依赖方向单向**（编译期）：`core → protocol`、`runtime → core`、`generator → protocol`、
   `springai → core`、`mcp → core`、`demo → runtime + springai`。适配器（模型 provider、身份 provider）
   放 core **之外**并依赖**它**——不是依赖 runtime：把应用的 boot 类拖到消费者的 classpath 上不是适配器该干的事
   （`ADR-0017` D2）。`runtime` 只被 demo（与两个适配器的 **test** 依赖）依赖。适配器缺席时 runtime 照常起
   （默认规划器条件注册，见硬规则 7）。
7. **适配器不许替模型越权**：规划器只**提议**（`IntentPlan` = 工具名 + 参数），风险/确认/审计/撤销
   一律由运行时下游无条件施加；且**治理元数据（风险级、是否需确认、撤销档）不得发给模型**。

## 构建与验证

```bash
bash scripts/release-gate.sh        # 一条命令跑全部确定性验证（本机约 9 分钟），末尾一行 PASS/FAIL 汇总
                                    # 覆盖：mvn verify · 快照漂移(按 pin) · 双语注释 · 三个生成物 demo · release 发布集合
                                    # LLM_ENV=1 时再加两个适配器 demo（需模型 key）
mvn test                            # 协议一致性 + 生成器 + 运行时 + 适配器
bash scripts/demo-generated-app.sh  # 生成 → 构建 → 独立运行 → 信任闭环
bash scripts/demo-changeability.sh  # 变更 → 再生成 → 手改存活 → 新规则生效
bash scripts/demo-migration.sh      # 变更 → 加性迁移 → 存量数据跟着走
bash scripts/demo-golden-path.sh    # 前端自己的模块打本运行时（需主仓 Web-Admin-Vue 检出）
bash scripts/demo-springai.sh       # 真模型上接缝（需 DEEPSEEK_API_KEY；无 key 时会明确报错退出）
bash scripts/demo-springai-task.sh  # 真模型驱动框架自己的多步循环，走 POST /ai/task（同需 DEEPSEEK_API_KEY）
bash scripts/check-bilingual-comments.sh  # 注释的双语形状（默认只报告；--strict 才拦；--self-test 自检）

# 第三种方言（PostgreSQL）真跑一遍：迁移 + ddl-auto=validate，对真的 PG。
# 无 KEELBASE_PG_URL 时该测试自动跳过；CI 侧对第三方言的闸是 MigrationDialectsTest（哪儿都跑）。
KEELBASE_PG_URL=jdbc:postgresql://localhost:5432/keelbase mvn test -Dtest=PostgresMigrationTest
```

CI（`.github/workflows/ci.yml`）门禁四件事：`conformance`（`mvn verify`，**跑 JDK 17 与 JDK 25 两条腿**
—— 产物仍按 17 构建，第二条腿只声明「构建与测试在新 JDK 上跑通」，不抬基线）+ `vector-drift`
（`sync-vectors.sh --check`）+ `bilingual-comments`（`check-bilingual-comments.sh --strict`；先跑 `--self-test`，
故解释器不对时会以自身失败、而不是以「报零」通过）+ `release-rehearsal`（发布集合在 release profile 下构建，
含 sources 与 javadoc；同样跑 **17 与 25 两条腿** —— javadoc 的 doclint 是最可能与新 JDK 不合的一环）。

⚠️ **打 `v*` tag 会触发发布**（`.github/workflows/release.yml`）：先复现向量、再查两仓漂移、再校验
发布集合，然后把**四个 artifact**（父 pom · `keelbase4j-protocol` · `keelbase4j-core` ·
`keelbase4j-springai`）签名上传到 Maven Central。发到 Central 收不回来，所以 tag 只打在**已推且 CI 绿**的提交上。

**发布集合 = reactor**（**不是** per-module 的 `deploy.skip`）：central-publishing 插件会把**构建里
所有模块**打包上传，`maven.deploy.skip` 拦不住它——**没有任何模块声明它**，所以 deploy 步的 `-pl` 是
这道题唯一的答案。第一次发布就是这么把六个模块全带上、并在其中一个上失败的。现在 deploy 步用
`-pl 'keelbase4j-springai,!keelbase4j-runtime' -am` 限制 reactor，另有一道检查确认结果恰为四个：
`-am` 保证依赖不会被忘掉，而 `runtime` 要**按名字排除**——它是**应用**、且 `-am` 会经适配器的**测试**
依赖够到它。**⚠ 排除它有一个代价，发布与彩排都要先付**：适配器**编译测试**时仍要解析那个 artifact，而被排除的
它在 reactor 里不存在 ⇒ Maven 会去 **Central** 找 `keelbase4j-runtime:jar:0.1.2` 并**失败**（那里没有、也永远
不会有）。所以两步都**先**跑 `mvn -DskipTests install -pl keelbase4j-runtime -am`，把它装进运行器本机仓库。
**这不是理论**：彩排任务第一次跑就是这么红的，而**本机那次「通过」只是被 `~/.m2` 里同版本的旧 artifact 救的**
—— 干净条件下同一个命令当场失败。
⚠️ 手动跑 `mvn -Prelease deploy`（不带 `-pl`）会**把所有模块都发上去**。

## 提交约定

- **提交前先审计**：`git status` 核对暂存范围 → `git diff` 审查 → 跑相关测试。发现的问题先修再提交。
- **文字说明一律英文在前、中文在后**——提交信息、Release 说明、CHANGELOG 条目、**新写**的注释都按这条。
  中英**各自成完整块**，块内不掺杂（专有名词、代码标识符、命令除外）；**唯一**允许中英混排的是标题行。
  只写一种语言 = 不合规。存量注释不回溯改写。
- **提交信息版式**：

      标题行：  <type>(<scope>): <English summary> — <中文摘要>     （可只写英文）
      正文一：  一整段英文（改了什么 / 为什么）
      正文二：  一整段中文（与英文段对应，不是逐条配对）

- **不带** `Co-Authored-By`。
- 消息用 `git commit -F <文件>`，**不要**用 `-m "…"`——正文含反引号时会被 shell 当命令替换吞掉。
- 未经明确指示**不推送**远程。

## 规划

**完整路线图不在本公开仓库。** 本线尚未完成、标记为「后续」的工作追加到**内部路线图**对应章节，
供后续按优先级推进；执行过程记录（做了什么 / commit / 验收）走内部执行日志，不内联进路线图。

**本线的定位与边界不在公开文档内裁定**：定位变更须走内部决策记录。对外表述克制——
技术可行性 ≠ 产品/市场验证。

## 关联

- 契约仓（向量 / wire schema / registry 真源）：`rain6fish/keelbase-contract`
- 主仓（散文协议）：`docs/protocols/ai-governance-protocol.md`（`rain6fish/KeelBase`）
- 本仓：`README.md` · `docs/ARCHITECTURE.md` · `conformance/vectors/README.md`
