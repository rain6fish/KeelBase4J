# F10 · 生成应用读面对对象/区间同语义（2026-11-21）

## 分支与提交

- 分支：`claude/gen-audit-readface-1121-01155b`（独立 worktree，未 push、未合 main）
- 代码与测试提交：`b53d457`（本报告随后单独提交）

## 改动文件:行

- `keelbase4j-generator/src/main/java/cn/com/keelbase/gen/JavaGenerator.java`
  - `SideEffectStore` 模板（约 1484–1600）：`Effect` 增加**内部**关联键 `executionId`；`record(...)` 接收它；
    新增 `findByResultTypeAndResultId(resultType, resultId)`，按**文本**比较业务主键（前导零有意义）。
  - `AuditChainStore` 模板（约 1679–1745）：`Row` 与 `append(...)` 增加 `executionId`；**不进哈希载荷**。
  - `GovernanceEngine` 模板（约 1855–1990）：每次受治理执行生成一个 `UUID` 关联键；intent 行、结果行、
    批准行与副作用记录共享它；拦截 / 待决 / 否决 / 撤销为 `null`（它们不是一次执行）。
  - `GovernanceController.logs(...)`（约 4498–4600）：声明 `until`、`objectType`、`objectId`；
    半个对象过滤 400；`until` 闭合区间（按行自身时间）；对象过滤在分页前施加。
  - 新增 `objectRowIds` / `sentenceRowIds` / `typedObjectKeySuffix` / `instant(field, value)` 辅助方法
    （约 4635–4760），文法与 `conformance/typed-object-key.json` 一致。
- `keelbase4j-generator/src/test/java/cn/com/keelbase/gen/GeneratorTest.java`（约 414–472）
  - 钉住：三个参数被声明、半个对象过滤 400、台账→执行 id→审计行、台账空时回落到句末带类型键、
    `until` 闭合区间、执行键不进冻结行形状与哈希载荷。

## 语义对照（生成物 ↔ 运行时）

| 项 | 运行时 `AuditController` / `AuditService` | 生成物 `GovernanceController.logs` |
|---|---|---|
| `until` | 两端闭合，按 `createdAt` | 同 |
| 半个对象过滤 | 400 | 400 |
| 对象解析 | 台账 `findByResultTypeAndResultId` → `executionId` → 审计行；范围内先问台账 | 同（台账改为生成物自己的 `SideEffectStore`） |
| 台账无命中 | 回落到 `detail` 句末带类型键（`endsWith`），区间为界 | 同 |
| 空集合 | 「没有匹配」，不是「没有过滤」 | 同（空 `Set` 什么都不选） |
| 拒绝项 | `agentId/orgId/denied` 400（本部署不记） | 同（改动前已如此） |

未新增契约字段、未改 `ai-audit-log-row` 行 schema；`executionId` 只存在于生成物内部类型，
既不进线上行形状，也不进哈希载荷（测试有断言）。

## 测试读数

- 改前红（把 `JavaGenerator.java` 临时还原为 HEAD、保留新测试）：
  `GeneratorTest.s2_generates_a_real_project_that_compiles` 失败于
  `'until' is declared, so Spring cannot drop it silently ==> expected: <true> but was: <false>`。
- 改后绿：
  - `mvn -pl keelbase4j-generator test -Dtest=GeneratorTest` → Tests run: 2, Failures: 0
  - `mvn -pl keelbase4j-generator test` → 17/17 绿（Changeability 1 · Generator 2 · RegenerationMerge 5 ·
    ThreeWayMerge 8 · ToolDescription 1）
  - 生成物编译：`GeneratorTest` 用 JDK 编译器实际编译生成源码，绿
  - 参照实现对照：`mvn -pl keelbase4j-runtime test -Dtest=AuditLogReadTest` → 13/13 绿（未改该模块）
  - `bash scripts/check-bilingual-comments.sh --strict` → 0 block(s) with English after Chinese
- 生成物端到端 demo（`bash scripts/demo-generated-app.sh`）：
  - 第一次在默认端口 18080 失败：本机另一并发会话的应用占着该端口，脚本的 `stop_app` 会
    `taskkill` 端口属主，本次应用（PID 4068）被外部清掉，`app.log` 无异常，失败请求返回 `000`。
    该失败与本次改动无关（失败请求是策略 PUT，且此前 `/audit/logs` 检查已通过）。
  - 改用独立端口 `PORT=18137` 重跑：**PASS** —— `PASS — generated app runs, the trust loop holds,
    and row scope is enforced`（exit 0；含 `a token approved by eight at once writes exactly once`、
    `revoke marks the effect revoked`、`the revocation left one line on the audit chain` 等全部检查）。
    `app.log` 无 `Exception`。

## 仍需用户裁定的点

1. **执行键是生成物内部新增的**。运行时的 `AuditLog` 有 `executionId` 列，生成物此前没有；为了精确复现
   「台账 → 执行 id → intent/结果/批准行」，在 `SideEffectStore.Effect` 与 `AuditChainStore.Row` 上加了
   内部字段。它不进冻结行形状、不进哈希载荷。若用户希望生成物**对外**也回 `executionId`（运行时读面
   现在会回它，且 v6 契约行 schema 含该字段），那是另一项决定，本次未做。
2. **`objectType` 的双重身份**：运行时对同一个参数先当台账的 `resultType`、再当句末键的可读词使用；
   生成物照此实现。若宿主控制台将来改为「发可读词、由服务端映射到 resultType」，两边需一起改。

## 提交

- `b53d457`（代码 + 测试）：`JavaGenerator.java`（+275/−39）、`GeneratorTest.java`（+60）
- 本报告随同一分支的下一个提交归档。

## 未做 / 边界

- 未 push、未合 main；未改 `KeelBase-Vue-Pro`（宿主侧一半已在 `claude/sad-elbakyan-358778`）。
- 未新增「用生成物真实调用 `/audit/logs?objectType=...`」的端到端 demo 检查（demo 脚本不在
  「生成器与它的测试」范围）；本次以结构断言 + 生成物编译 + 既有 demo 的回归通过作证。
  若需要行为级端到端证据，建议在 `scripts/demo-generated-app.sh` 增加一节（下一项工作）。

