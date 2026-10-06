-- SPDX-License-Identifier: Apache-2.0
--
-- JV-24 — seed data for the flagship demo, applied **out of band**: the generated application keeps its
-- database in a single-process H2 file, so this runs while the application is stopped. It is applied by
-- `scripts/demo-flagship-generated.sh`, never by the generator: demo data must not ride along into every
-- generated application (the generator is the product surface).
--
-- Idempotent by construction — fixed ids, inserted only when absent — because the demo is meant to be
-- rerun: rerunning must not double the rows, and must not touch anything the demo itself wrote.
--
-- JV-24 —— 旗舰演示的种子数据，**带外**灌入：生成物的库是**单进程** H2 文件，故这份在**应用停着**时跑。
-- 它由 `scripts/demo-flagship-generated.sh` 应用，**从不**由生成器发出：演示数据不该跟着进每一个生成的
-- 应用（生成器是产品面）。
--
-- **幂等**是构造出来的——固定 id、缺了才插——因为演示本来就要反复跑：重跑不该把行翻倍，也不该碰演示
-- 自己写下的东西。
--
-- 身份不在这里造：生成物自带 `identity/LocalIdentities`（alice · bob · carol），本文件只造**业务行**。
-- 也刻意不造「有风险的客户」——生成物的 `analyze_customer_risk` 是桩、没有那个信号（见 `JV-24-方案_2026-10-06.md` §7.1）。

-- alice 名下的两行：她是普通用户，演示里「只看到自己的行」的那一侧。
INSERT INTO customers (id, name, level, owner_user_id)
SELECT 1001, '晨光科技', 'vip', 'alice'
WHERE NOT EXISTS (SELECT 1 FROM customers WHERE id = 1001);

INSERT INTO customers (id, name, level, owner_user_id)
SELECT 1002, '远山医疗', 'normal', 'alice'
WHERE NOT EXISTS (SELECT 1 FROM customers WHERE id = 1002);

-- bob 名下的一行：**存在但 alice 不该看见它** —— 这条就是范围断言的负半（`check_absent`）。
INSERT INTO customers (id, name, level, owner_user_id)
SELECT 1003, '海州物流', 'normal', 'bob'
WHERE NOT EXISTS (SELECT 1 FROM customers WHERE id = 1003);

-- 一条已有跟进：让客户详情页与跟进列表**不是空的**（演示的写那一拍会**新造**一条，这条只是背景）。
INSERT INTO follow_ups (id, customer_id, note, due_date, owner_user_id)
SELECT 2001, 1001, '首访：已确认续约意向', '2026-10-20', 'alice'
WHERE NOT EXISTS (SELECT 1 FROM follow_ups WHERE id = 2001);
