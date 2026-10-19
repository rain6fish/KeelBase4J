# Reference project — PM (project + task) inside this runtime

The PM reference project is the **second sample domain** in the KeelBase4J runtime. It exists to show
that the trust loop is a property of the runtime, not of the CRM entities: the same gate, the same
audit chain and the same local compensation run over projects and tasks with no new seam.

It is deliberately small — **one project entity and one task entity**. Milestones and members are
left for a later slice rather than stubbed here.

## The domain

| Entity | Table | What it carries |
|---|---|---|
| `PmProject` | `pm_projects` | name, status (`active` / `on_hold` / `done`), declared risk level (`low` / `medium` / `high`), description, owner, org/dept |
| `PmTask` | `pm_tasks` | project id, title, status (`open` / `done`), due date, owner, org/dept, soft-delete marker |

Both rows carry the scope facts the CRM tables carry, so the same typed row-range predicate applies
(`own` for a user, `all` for an administrator). The due date is a `DATE`, not free text: the
deadline-risk analysis compares it.

## The tools and their gates

| Tool | Risk | Gate | Side effect / compensation |
|---|---|---|---|
| `analyze_project_risk` | R1 | auto-executes | none (read) |
| `create_pm_task` | R3 | waits for the operator | creates a soft-deletable `pm_task`; revoke is local compensation |
| `escalate_project` | R4 | waits for a **second person** (ADR-0018) | creates a soft-deletable `pm_task`; revoke is local compensation |
| `delete_project` | R5 | **blocked on the risk level**, before the policy is read | none — it is never executed |

The R5 tool is what makes the block branch demonstrable. It declares a result type so the inventory
counts it as a write, and the integration-health report deliberately does not warn about its missing
compensation: a write that can never run has no side effect to leave uncompensated.

## Run it

```bash
bash scripts/demo-pm-reference.sh
```

The script builds the runtime and the demo module, starts the runtime on port 18107, mints
delegation tokens for `alice` (user), `carol` (administrator) and `bob` (second user), then calls
`scripts/pm-reference-e2e.mjs`. That script drives the API and asserts every beat. A run ends with:

```text
PASS pm-reference: 36 checks
```

The readings it prints include:

- the R1 analysis: `level=critical`, `overdueTasks=1` — deterministic, from the seeded overdue task;
- the R3 proposal holding `pending`, then `executed` with an `effectId`, then `revoked` with the task
  gone from the live set and an `effect_revoke` line on the audit chain;
- the R4 row visible only in the administrator queue, the initiator refused with 403, a second person
  approving it, and the written task owned by the initiator;
- the R5 call answered `blocked (risk_policy)` with an audit line and no effect;
- a cross-user read refused with 403 and audited;
- `GET /keelbase/status`: 7 tools, `R5 = 1`, `effectiveBlocked = 1`, four dimensions healthy.

## What is verified

- `PmReferenceLoopTest` — 6 cases over HTTP: R1 auto, R3 confirm + revoke, R4 second-person approval,
  an administrator cannot approve their own action, R5 blocked and audited, a foreign row refused and
  audited.
- `scripts/demo-pm-reference.sh` — the 36-check end-to-end reading above.
- **Reverse proof**: changing `create_pm_task` from R3 to R1 makes
  `PmReferenceLoopTest#aWriteWaitsForTheOperatorAndARevokeCompensatesItLocally` red (`expected: <1>
  but was: <0>` on the pending count). The gate is what the case measures, not an incidental status
  code.

## Boundaries

- **Not a product UI.** The runtime has no interface of its own; the main repository's console is the
  UI, and this reference is driven over the same API.
- **No model required.** The default deterministic planner routes on the word `项目`; a deployment
  that declares its own `ToolCallPlanner` replaces it. The router's limit is honest: a PM message that
  does not say `项目` is routed as CRM.
- **Milestones and members are not implemented.** The slice is project + task only.
- **Compensation is local.** Both write tools create soft-deletable rows. There is no external system
  to compensate, and the demo does not claim one.
- **Not the integrator-kit PM example.** `KeelBase-java-starter` has a PM reference that exports
  `@KeelbaseTool`s to a remote KeelBase. This one is the opposite direction: it runs the governed
  runtime itself. The two share the domain, not the seam.

## Files

| Path | What |
|---|---|
| `keelbase4j-core/.../domain/PmProject.java` · `PmTask.java` | entities |
| `keelbase4j-core/.../domain/PmProjectRepository.java` · `PmTaskRepository.java` | repositories |
| `keelbase4j-core/.../tool/AnalyzeProjectRiskTool.java` · `CreatePmTaskTool.java` · `EscalateProjectTool.java` · `DeleteProjectTool.java` | the four tools |
| `keelbase4j-core/.../effect/PmTaskCompensator.java` | local compensation for `pm_task` |
| `keelbase4j-core/.../web/PmProjectController.java` | the read half (`/pm/projects`, `/pm/projects/{id}/tasks`) |
| `keelbase4j-core/.../db/keelbase-migration/{h2,mysql,postgresql}/V15__sample_pm_module_state.sql` | schema, three dialects |
| `keelbase4j-runtime/.../SeedData.java` | demo seed (one overdue task, one due soon) |
| `keelbase4j-runtime/src/test/.../PmReferenceLoopTest.java` | the six governance cases |
| `scripts/demo-pm-reference.sh` · `scripts/pm-reference-e2e.mjs` | the one-command demo |

---

# 参考项目——本运行时内的 PM（项目 + 任务）

PM 参考项目是 KeelBase4J 运行时里的**第二个示例域**。它存在是为了说明：信任闭环是**运行时**的属性，不是
CRM 实体的属性——同一道闸、同一条审计链、同一种本地补偿，换成项目与任务、**不新增任何接缝**。

它刻意做得很小——**一个项目实体加一个任务实体**。里程碑与成员留给后续切片，不在这里先做空壳。

## 域

| 实体 | 表 | 携带什么 |
|---|---|---|
| `PmProject` | `pm_projects` | 名称、状态（`active` / `on_hold` / `done`）、声明的风险级（`low` / `medium` / `high`）、描述、所有者、组织/部门 |
| `PmTask` | `pm_tasks` | 项目 id、标题、状态（`open` / `done`）、到期日、所有者、组织/部门、软删标记 |

两张行都带着 CRM 表所带的那些范围事实，故同一套**带类型的行范围谓词**照常生效（普通用户 `own`、管理员
`all`）。到期日是 `DATE` 而不是自由文本：延期风险分析要比较它。

## 工具与它们的闸

| 工具 | 风险级 | 闸 | 副作用 / 补偿 |
|---|---|---|---|
| `analyze_project_risk` | R1 | 自动执行 | 无（读） |
| `create_pm_task` | R3 | 等操作者本人 | 创建可软删的 `pm_task`；撤销即本地补偿 |
| `escalate_project` | R4 | 等**第二个人**（ADR-0018） | 创建可软删的 `pm_task`；撤销即本地补偿 |
| `delete_project` | R5 | **按风险级阻断**，策略根本读不到它 | 无——**从不执行** |

R5 工具的存在是为了让阻断分支**可演示**。它声明了结果类型，故清单把它算成**写**；而接入健康度报告**刻意
不**为它缺补偿而告警：一次永远跑不了的写，没有会留下的未补偿副作用。

## 跑起来

```bash
bash scripts/demo-pm-reference.sh
```

脚本会构建运行时与 demo 模块、在 18107 端口启动运行时、为 `alice`（普通用户）、`carol`（管理员）与
`bob`（第二个普通用户）签发委托令牌，然后调用 `scripts/pm-reference-e2e.mjs`。后者驱动 API 并逐拍断言。
一次运行以这一行收尾：

```text
PASS pm-reference: 36 checks
```

它打印的读数包括：

- R1 分析：`level=critical`、`overdueTasks=1` —— 确定性的，来自种下的逾期任务；
- R3 提议先停在 `pending`，批准后 `executed` 并带 `effectId`，撤销后任务从活集消失、审计链上多一行
  `effect_revoke`；
- R4 的行只在管理员队列可见、发起人批准被 403、第二个人批准后执行、写下的任务归**发起人**；
- R5 调用答 `blocked (risk_policy)`，留审计行、无副作用；
- 跨用户读被 403 拒绝且留痕；
- `GET /keelbase/status`：7 个工具、`R5 = 1`、`effectiveBlocked = 1`、四维健康。

## 验证了什么

- `PmReferenceLoopTest` —— 6 个走 HTTP 的用例：R1 自动、R3 确认 + 撤销、R4 双人审批、管理员也不能批准
  自己的动作、R5 阻断且留痕、越权读被拒且留痕。
- `scripts/demo-pm-reference.sh` —— 上面那份 36 项端到端读数。
- **反向可证**：把 `create_pm_task` 从 R3 改成 R1，`PmReferenceLoopTest#
  aWriteWaitsForTheOperatorAndARevokeCompensatesItLocally` 立刻转红（待确认数 `expected: <1> but was:
  <0>`）。用例测的是**那道闸**，不是一个顺手的状态码。

## 边界

- **不是产品界面。** 运行时没有自己的界面；界面在主仓的控制台，本参考项目走同一条 API。
- **不需要模型。** 默认的确定性路由器按「项目」这个词路由；声明了自己的 `ToolCallPlanner` 的部署会替换它。
  路由器的边界是诚实的：不带「项目」的 PM 消息会被当成 CRM。
- **里程碑与成员未实现。** 本切片只有项目 + 任务。
- **补偿是本地的。** 两个写工具都创建可软删的行。没有外部系统可补偿，演示也不宣称有。
- **不是 starter 的 PM 示例。** `KeelBase-java-starter` 有一个 PM 参考，把 `@KeelbaseTool` **导出给远端**
  KeelBase；这一份方向相反：它自己跑**受治理的运行时**。两者共享的是域，不是接缝。

## 文件

| 路径 | 是什么 |
|---|---|
| `keelbase4j-core/.../domain/PmProject.java` · `PmTask.java` | 实体 |
| `keelbase4j-core/.../domain/PmProjectRepository.java` · `PmTaskRepository.java` | 仓储 |
| `keelbase4j-core/.../tool/AnalyzeProjectRiskTool.java` · `CreatePmTaskTool.java` · `EscalateProjectTool.java` · `DeleteProjectTool.java` | 四个工具 |
| `keelbase4j-core/.../effect/PmTaskCompensator.java` | `pm_task` 的本地补偿 |
| `keelbase4j-core/.../web/PmProjectController.java` | 读半（`/pm/projects`、`/pm/projects/{id}/tasks`） |
| `keelbase4j-core/.../db/keelbase-migration/{h2,mysql,postgresql}/V15__sample_pm_module_state.sql` | schema，三种方言 |
| `keelbase4j-runtime/.../SeedData.java` | 演示种子（一条逾期、一条即将到期） |
| `keelbase4j-runtime/src/test/.../PmReferenceLoopTest.java` | 六个治理用例 |
| `scripts/demo-pm-reference.sh` · `scripts/pm-reference-e2e.mjs` | 一条命令的演示 |
