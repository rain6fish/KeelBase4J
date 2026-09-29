-- The sample business module's tables: the customers the reference tools read, and the follow-up notes
-- they write. MySQL translation of the h2 sibling — same tables, same columns, same order, different
-- dialect.
--
-- They ride in the core because the core is what ships the tools and the endpoints that use them, and
-- a validation of the schema checks every mapped entity — including these two.
--
-- They are separated from V1 on purpose, and the separation is a statement rather than a filing choice:
-- these are not governance state. A customer table is the kind of thing a host already has, and the
-- embedding rule is that whatever the host has, the host keeps (ADR-0017 §1.1). Whether this sample
-- module should be separable from the embeddable core — a deployment that wants the trust loop without
-- the sample CRM — is a live question the migrations make visible instead of hiding.
--
-- Dialect: MySQL, as in V1; the same two type translations apply (`AUTO_INCREMENT` for identity,
-- `DATETIME(6)` for `Instant`). Immutable once applied; changes are new versions.
--
-- 示例业务模块的表：参考工具读的客户，以及它们写的跟进记录。这是 h2 同族文件的 MySQL 译本——同样的
-- 表、同样的列、同样的顺序，只是方言不同。
--
-- 它们随 core 一起走，因为 core 正是发出那些工具及其端点的东西，而 schema 校验会检查**每一个**被映射
-- 的实体——包括这两张。
--
-- 它们与 V1 分开是有意的，而这个分开是一个**陈述**而非归档习惯：这些不是治理状态。客户表正是宿主
-- 本来就有的那类东西，而嵌入规则是「宿主有什么，宿主就留着」（ADR-0017 §1.1）。这个示例模块是否应当
-- 与可嵌入的 core 拆开——也就是一个只想要信任闭环、不想要示例 CRM 的部署——是一个**仍然活着的问题**，
-- 迁移把它**显形**而不是藏起来。
--
-- 方言：MySQL，同 V1；同样的两处类型翻译（身份列用 `AUTO_INCREMENT`，`Instant` 用 `DATETIME(6)`）。
-- 一旦应用即不可变；变更走新版本。

CREATE TABLE customers (
    id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    -- low | medium | high.
    level         VARCHAR(255) NOT NULL,
    owner_user_id VARCHAR(255) NOT NULL,
    -- The row range facts: a range may name an organization or a department, so a row needs both or
    -- neither. Nullable, and null tightens to the owner rather than widening.
    org_id        BIGINT,
    dept_id       BIGINT
);

CREATE TABLE follow_ups (
    id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_id   BIGINT       NOT NULL,
    note          VARCHAR(255) NOT NULL,
    due_date      VARCHAR(255),
    owner_user_id VARCHAR(255) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    -- Soft delete: revoking this write is a local compensation, so the row stays and is marked.
    deleted_at    DATETIME(6)
);
