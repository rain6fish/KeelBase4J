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
