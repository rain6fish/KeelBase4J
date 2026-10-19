-- The second sample business module: the projects the PM reference tools read, and the task rows
-- they write. MySQL translation of the h2 sibling — same tables, same columns, same order, different
-- dialect.
--
-- These are not governance state, for the reason V2 gives for the CRM tables.
--
-- Dialect: MySQL — `AUTO_INCREMENT` for identity, `DATETIME(6)` for `Instant`. Immutable once
-- applied; changes are new versions.
--
-- 第二个示例业务模块：PM 参考工具读的项目，以及它们写的任务行。这是 h2 同族文件的 MySQL 译本 —— 同样的
-- 表、同样的列、同样的顺序，只是方言不同。
--
-- 这些**不是**治理状态，理由与 V2 对 CRM 两张表说的一样。
--
-- 方言：MySQL —— 身份列用 `AUTO_INCREMENT`，`Instant` 用 `DATETIME(6)`。一旦应用即不可改；变更走新版本。

CREATE TABLE pm_projects (
    id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    -- active | on_hold | done.
    status        VARCHAR(255) NOT NULL,
    -- low | medium | high.
    risk_level    VARCHAR(255) NOT NULL,
    description   VARCHAR(1000),
    owner_user_id VARCHAR(255) NOT NULL,
    -- The row range facts, the same shape `customers` carries.
    org_id        BIGINT,
    dept_id       BIGINT,
    created_at    DATETIME(6) NOT NULL
);

CREATE TABLE pm_tasks (
    id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id    BIGINT       NOT NULL,
    title         VARCHAR(255) NOT NULL,
    -- open | done.
    status        VARCHAR(255) NOT NULL,
    -- The deadline-risk analysis compares this date, so it is a date rather than free text.
    due_date      DATE,
    owner_user_id VARCHAR(255) NOT NULL,
    org_id        BIGINT,
    dept_id       BIGINT,
    created_at    DATETIME(6) NOT NULL,
    -- Soft delete: revoking this write is a local compensation, so the row stays and is marked.
    deleted_at    DATETIME(6)
);
