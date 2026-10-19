# Getting the first token — what a deployment does

> **`JV-44`, decision **A**: this page closes a **documentation gap**, not a capability gap.**
> Core deliberately does **not** own identity infrastructure (`docs/ARCHITECTURE.md` §5b; ADR-0004 D3/D4). It has **no `POST /auth/login` on purpose**: authentication happens at the entry and core only verifies. What was missing was never code — it was a written, executable answer to *"so where does the first token come from?"*. This is that answer.

## 1. The rule in one line

**Core never issues a token. The deployment mints it, out of band; core verifies it.**

## 2. What core exposes — and what it deliberately does not

| Endpoint | Auth | Note |
|---|---|---|
| `POST /auth/login` | — | **Does not exist, by decision** (see the note above) |
| `GET /auth/me` · `/auth/me/permissions` | bearer | Anonymous → **401** |
| `GET /auth/oauth/providers` · `/auth/login-stats` | public | the protocol entry lists the configured OIDC/OAuth2 providers |
| `GET /app/capabilities` · `/app/provenance` | public | self-description |

The security configuration is **STATELESS**: there is no session to create, and none is created.

## 3. The token: a delegation token, minted by the deployment

- **Format** — JWT, `HS256`, signed with the **shared delegation secret**.
- **Verification** — the **frozen protocol's own** `DelegationToken.verify`, deliberately not a second JWT implementation: two verifiers are two things to keep in step.
- **Entry** — `DelegationTokenAuthenticationFilter`. A request that fails that check **never reaches a controller**.
- **Claims** — `sub` (the deployment's, naming the local user; `oidcSub` carries the upstream OIDC subject when there is one) · `aud` · `iss` · `iat` · `exp`.
- **`iss` must be exactly `keelbase`.** The verifier rejects any other issuer — this is not a free-form claim.
- **`aud` must be this system's audience** (when one is configured). A token minted for another system is refused with **401**.
- **`sub`** names the local user the action is attributed to (`local:<userId>` when there is no OIDC subject).
- **Keep the TTL short.** The reference issuer uses 300 s.

## 4. The two ways a deployment produces it

**(a) On behalf of a user your own front end has already authenticated.**
Mint the token in your login path — deployment secret, `aud` = core's audience, `sub` = the mapped local user, short TTL — and hand it to the front end. The front end presents `Authorization: Bearer <token>` on every call. **Core's side is one filter and one verifier.**

**(b) Behind an OIDC / OAuth2 provider.**
The provider is the protocol entry (`GET /auth/oauth/providers` lists what is configured). After the provider has verified the user, the deployment mints the delegation token from that subject and carries it in `oidcSub`.

Either way the minting code is **the deployment's**, not core's.

## 5. Where the working examples are

- **Tests** — `TestTokens.forUser(...)`, as used by `AuthenticationTest` (which also pins the wrong-audience 401 and the anonymous 401).
- **The reference application** — this project's TypeScript repository issues a short-lived delegation token from its own login (`POST /auth/delegation-token`, AI Bridge §5); a Java-side system verifies it with the same shared secret.

## 6. What this page does not start

Adding `POST /auth/login` to core would make core **hold identity infrastructure** — the thing D4 refuses. That road (option **B** in the ruling) was considered and **not taken**. If it is ever revisited, the ADR's wording moves **first**; the code does not move alone.

## 7. Evidence packages: where they come from

> **`JV-64`, decision **乙** (2026-10-17): this page states an absence rather than closing it.** No
> evidence-export endpoint is added, here or on this line.

This deployment keeps an audit chain and answers `GET /audit/verify`, which recomputes and verifies that
chain over the rows this runtime recorded. It does **not** export an evidence package or an evidence
root: there is no such endpoint here, and none is planned on this line. If a Java host deployment needs
an evidence package, obtain it from the **TypeScript governance console**, not from this runtime.

Any coverage statement that accompanies such a package must also carry one more fact (`D28 ④`): where
the runtime is embedded in a host that owns the business write, that write and its audit row are not
written in one transaction, so a crash between them leaves an executed write unaudited.

## 8. The coverage statement this deployment can answer

`GET /audit/coverage?since=&until=&userId=` answers what a window was read from and what reading it
cannot show. It is gated by `read` on `AuditLog`, so administrators and a deployment-declared audit
reader reach it and an ordinary caller does not; it defaults to the last 30 days. The response names
the registry revision it was judged against, the scope, the sources actually read, a **derived**
verdict and the applicable gaps — every gap comes from the vendored registry, not from this page.

Declare the deployment facts under `keelbase.coverage.*`:

| setting | standalone | embedded host |
|---|---|---|
| `host` | `none` | `yudao` or `ruoyi` |
| `host-owns-business-write` | `false` | `true` when the host, not the runtime, writes the business row |
| `host-own-ai-path` | `false` | `true` when the host runs an AI path of its own |

With those set, a host deployment's statement declares `host-write-not-in-same-transaction`, and —
when the host has its own AI path — `host-ai-path-not-governed`. The delivery acceptance form must
carry the applicable gap ids from this statement; an acceptance form that cannot be read for its
coverage is not a completed delivery.

---

# 拿到第一枚令牌 —— 部署方要做什么

> **`JV-44`，裁决 **A**：这一页补的是**文档缺口**，不是能力缺口。**
> core 有意**不持有**身份基础设施（`docs/ARCHITECTURE.md` §5b；ADR-0004 D3/D4）。它**没有 `POST /auth/login`**，那是**刻意的**：认证发生在入口，core 只做验证。缺的从来不是代码，而是「**那第一枚令牌到底从哪来**」的**一份写下来、能照做的答案**。这一页就是它。

## 1. 一句话规则

**core 从不签发令牌。部署方在带外铸；core 负责验。**

## 2. core 公开什么 —— 以及它刻意不做什么

| 端点 | 认证 | 说明 |
|---|---|---|
| `POST /auth/login` | — | **不存在，这是决定**（见上面的说明） |
| `GET /auth/me` · `/auth/me/permissions` | bearer | 匿名 → **401** |
| `GET /auth/oauth/providers` · `/auth/login-stats` | 公开 | 协议入口；前者列出已配置的 OIDC/OAuth2 提供方 |
| `GET /app/capabilities` · `/app/provenance` | 公开 | 自述面 |

安全配置是 **STATELESS**：**没有会话要建，也不会建**。

## 3. 那枚令牌：委托令牌，由部署方铸

- **格式** —— JWT、`HS256`，用**共享的委托密钥**签。
- **验证** —— 用**冻结协议自己的** `DelegationToken.verify`，刻意**不是**第二套 JWT 实现：两个验证器就是两件要同步的东西。
- **入口** —— `DelegationTokenAuthenticationFilter`。没验过的请求**根本到不了控制器**。
- **声明** —— `sub`（**由签发方给**，指名本地用户；有上游 OIDC 主体时用 `oidcSub` 带）· `aud` · `iss` · `iat` · `exp`。
- **`iss` 必须恰为 `keelbase`**。验签器拒绝其他任何签发者——**它不是个自由填的声明**。
- **`aud` 必须是本系统的受众**（配置了期望受众时）。为别的系统铸的令牌会被**401** 拒掉。
- **`sub`** 指名这次动作记在哪个本地用户头上（没有 OIDC 主体时形如 `local:<userId>`）。
- **TTL 取短。**参照签发方用 **300 秒**。

## 4. 部署方产它的两条路

**（甲）代一个你自己前端已经认证过的用户。**
在你们的登录路径里铸——部署密钥、`aud` = core 的受众、`sub` = 映射后的本地用户、短 TTL——交给前端。前端每次调用带 `Authorization: Bearer <token>`。**core 这一侧就是一个过滤器加一个验证器。**

**（乙）在 OIDC / OAuth2 提供方后面。**
提供方就是协议入口（`GET /auth/oauth/providers` 列出已配置的）。提供方验过用户之后，由部署方**从那具主体铸**委托令牌，放进 `oidcSub`。

两条路里，铸令牌的代码都是**部署方的**，不是 core 的。

## 5. 能照着做的例子在哪

- **测试** —— `TestTokens.forUser(...)`，`AuthenticationTest` 就用的它（那份测试同时钉住了**受众不对 → 401** 与**匿名 → 401**）。
- **参照应用** —— 本项目 TypeScript 仓从它自己的登录签发短期委托令牌（`POST /auth/delegation-token`，AI Bridge §5）；Java 侧的系统用**同一个共享密钥**验。

## 6. 这一页没有开启什么

给 core 加 `POST /auth/login`，等于让 core **开始持有身份基础设施**——正是 D4 拒绝的那件事。那条路（裁决里的 **B**）**考虑过、没有走**。若将来重开，**先**动 ADR 的措辞，**代码不单独先动**。

## 7. 证据包从哪来

> **`JV-64`，裁决 **乙**（2026-10-17）：这一页把一处**缺席**写明白，而不是去补上它。**
> 这里**不**新增任何证据导出端点，本线也不加。

本部署持有一条审计链，并对外提供 `GET /audit/verify`——它重算并校验这条链在**本运行时记下的行**上的
完整性。它**不**导出证据包或证据根：这里没有这样的端点，本线当前也**没有**计划加。Java 宿主部署若需要
证据包，请从 **TypeScript 治理控制台**取得，**不是**从本运行时取得。

随这类证据包给出的覆盖声明还必须带上另一件事（`D28 ④`）：当运行时被嵌入一个**自己持有业务写**的宿主时，
那次写与它的审计行**不在同一个事务里**写，两者之间崩溃会留下一条**已执行、未审计**的写。

## 8. 本部署能答出的覆盖声明

`GET /audit/coverage?since=&until=&userId=` 答出一个时间窗是从什么读来的、以及这么读**看不了**什么。
闸是 `AuditLog` 上的 `read`，故管理员与部署方点名的审计读者够得到、普通调用方够不到；默认最近 30 天。
响应点名判断所依据的登记表版本、口径、真的读过的源、**算出来**的结论、以及适用的缺口 —— 每一条缺口都来自
vendored 的登记表，不是这一页写的。

部署事实在 `keelbase.coverage.*` 下声明：

| 设置 | 独立运行时 | 嵌入宿主 |
|---|---|---|
| `host` | `none` | `yudao` 或 `ruoyi` |
| `host-owns-business-write` | `false` | 业务那一行由**宿主**写、不由运行时写时为 `true` |
| `host-own-ai-path` | `false` | 宿主有自己的一条 AI 路径时为 `true` |

设好之后，宿主部署的声明会带上 `host-write-not-in-same-transaction`；宿主另有自己的 AI 路径时还要带上
`host-ai-path-not-governed`。交付验收单必须携带这份声明里**实际适用**的缺口 id；一份读不到覆盖声明的
验收单，不算一次已完成的交付。
