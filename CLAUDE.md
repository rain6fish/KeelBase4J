# CLAUDE.md — KeelBase4J

Java runtime for **KeelBase**, a conformance implementation of the KeelBase AI Governance Protocol.
Authoritative protocol + vectors live in the main repository (`rain6fish/KeelBase`); this repository
only **consumes** them.

## 硬规则（红线，Day 1 钉死）

1. **实现冻结契约，不翻译参照实现。** 协议是唯一真源，不是 TypeScript 代码。兼容性由复现
   语言无关向量证明——同一批文件，任何其他实现都该用。
2. **`conformance/vectors/` 是只读快照，禁止手改。** 刷新一律跑 `scripts/sync-vectors.sh`。
   协议变更**先在主仓**落地（向量 → 实现），本仓只消费；两仓漂移由 CI 的 `vector-drift` 拦。
3. **生成物 = 真实、可独立运行的 Java/Spring 源码。** 不是运行期读 JSON 解释的低代码：
   文件是能打开、读懂、手改的 `.java`；生成器退场后应用自持。
4. **产物基线 Java 17**（`--release 17`）。
5. **`keelbase4j-protocol` 零第三方依赖**（JDK only）；JUnit 仅 test scope。它正是生成物依赖的
   那个 artifact——往它上面加依赖，等于加到每一套生成应用上。runtime / generator 用 Spring 不受此限。
6. **依赖方向单向**：`runtime → protocol`、`generator → protocol`、`springai → runtime`；**runtime 不被
   任何模块依赖**。适配器（模型 provider、身份 provider）放 runtime **之外**并依赖它，反向依赖即破接缝。
   适配器缺席时 runtime 照常起（默认规划器条件注册，见硬规则 7）。
7. **适配器不许替模型越权**：规划器只**提议**（`IntentPlan` = 工具名 + 参数），风险/确认/审计/撤销
   一律由运行时下游无条件施加；且**治理元数据（风险级、是否需确认、撤销档）不得发给模型**。

## 构建与验证

```bash
mvn test                            # 协议一致性 + 生成器 + 运行时 + 适配器
bash scripts/demo-generated-app.sh  # 生成 → 构建 → 独立运行 → 信任闭环
bash scripts/demo-changeability.sh  # 变更 → 再生成 → 手改存活 → 新规则生效
bash scripts/demo-migration.sh      # 变更 → 加性迁移 → 存量数据跟着走
bash scripts/demo-golden-path.sh    # 前端自己的模块打本运行时（需主仓 Web-Admin-Vue 检出）
bash scripts/demo-springai.sh       # 真模型上接缝（需 DEEPSEEK_API_KEY；无 key 时会明确报错退出）
```

CI（`.github/workflows/ci.yml`）门禁两件事：`conformance`（`mvn verify`）+ `vector-drift`
（`sync-vectors.sh --check`）。

⚠️ **打 `v*` tag 会触发发布**（`.github/workflows/release.yml`）：先复现向量、再查两仓漂移、再校验
发布集合，然后把**父 pom 与 `keelbase4j-protocol`**签名上传到 Maven Central。发到 Central 收不回来，
所以 tag 只打在**已推且 CI 绿**的提交上。

**发布集合 = reactor**（**不是** per-module 的 `deploy.skip`）：central-publishing 插件会把**构建里
所有模块**打包上传，`maven.deploy.skip` 拦不住它——第一次发布就是这么把六个模块全带上、并在其中一个
上失败的。所以 deploy 步用 `-pl keelbase4j-protocol -am` 限制 reactor，另有一道检查确认结果恰为两个。
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

- 主仓（协议真源）：`docs/protocols/ai-governance-protocol.md` · `Server-NestJS/specs/protocol/`
- 本仓：`README.md` · `docs/ARCHITECTURE.md` · `conformance/vectors/README.md`
