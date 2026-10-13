-- The operations audit: what was done to the business, as opposed to what the AI proposed.
--
-- This is a **second chain**, and that it is a second one is the reason it exists: `ai_audit_logs`
-- records what a tool call came to, and this records the business writes themselves — including the
-- ones no AI was involved in. The console's operations page reads this one; its decision trace page
-- reads the other.
--
-- What is deliberately **not** recorded here, and why:
--   * `requestBody` — the reference redacts PII field by field before storing a body, and this runtime
--     has no PII declaration to redact by. A body stored without one would be a leak, so none is stored.
--   * `changes` — the field-level before/after diff is the reference's own feature; nothing here
--     produces one yet.
--   * `featureKey` / `featureFallback` / `businessEvent` — those name features a mapping declares, and
--     this runtime has no such map.
--   * `prevHash` / `hash` — the frozen object marks both optional, and a chained audit needs a second
--     chain head and its own locking; doing that half-way would be worse than not doing it.
-- Each of those is a boundary rather than an oversight, and each is stated on the object's row in
-- `docs/wire-object-endpoints.md`.
--
-- 操作审计：**对业务做了什么**，与「AI 提议了什么」相对。
--
-- 这是**第二条链**，而它是第二条的理由正是它存在的理由：`ai_audit_logs` 记的是「一次工具调用最后怎么了」，
-- 这条记的是**业务写本身** —— 包括**没有 AI 参与**的那些。控制台的操作审计页读这一条，它的决策轨迹页读另一条。
--
-- 这里**刻意不记**的东西，以及为什么：
--   * `requestBody` —— 参照实现存正文前会**逐字段**打码 PII，而本运行时**没有 PII 声明**可依。不打码就存正文
--     等于泄露，所以**一条都不存**。
--   * `changes` —— 字段级前后差异是参照实现自己的功能；本运行时还没有产出它的东西。
--   * `featureKey` / `featureFallback` / `businessEvent` —— 那些是**映射**声明的功能名，本运行时没有这份映射。
--   * `prevHash` / `hash` —— 冻结对象把两者都标为**可选**，而一条链式审计需要**第二个链头**和自己的加锁；
--     做成一半比不做更糟。
-- 每一条都是**边界**而不是疏漏，且都写在 `docs/wire-object-endpoints.md` 该对象那一行里。

CREATE TABLE operation_audit_logs (
    id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id     VARCHAR(255),
    action      VARCHAR(32) NOT NULL,
    method      VARCHAR(16) NOT NULL,
    path        VARCHAR(512) NOT NULL,
    target_id   VARCHAR(255),
    status_code INTEGER,
    ip          VARCHAR(64),
    user_agent  VARCHAR(512),
    created_at  DATETIME(6) NOT NULL
);
