-- C-3 backfill: the rows written before the two columns existed, given the values their writers would
-- have given them.
--
-- `V7__audit_row_outcome_and_tool` added `outcome` and `tool` as plain nullable columns and filled
-- nothing, so every row written earlier carries NULL in both. A reader that then switches to the
-- columns would not merely show blanks — it would report different rates than the free-text reading
-- it replaced, which is the one thing the switch is supposed to leave alone.
--
-- The rules below are the writers' own (`GovernedExecutionEngine`, `SideEffectService`), read off the
-- strings they write. Unlike the TypeScript side, where two shapes were indistinguishable after the
-- fact, everything here is derivable: this runtime's `detail` states the whole outcome in words, and
-- there is no column it could have carried instead — this table has no structured refusal-reason
-- column, only the sentence.
--
-- **Only `outcome IS NULL` rows are touched.** Rows the current writers produced already carry both
-- values, and writing them again could only lose information. The free-text rule is deliberately
-- coarser than the column in one place: `blocked` and `refused` are two gates with a word each, while
-- a reader that matched any sentence containing `refused` folded them together. Filling the column
-- from the sentence is what stops that fold from surviving the switch.
--
-- The tool name is `detail`'s first token. `POSITION` and `SUBSTRING … FROM … FOR` are standard SQL
-- and all three dialects implement them as written, so this file has no dialect twin to keep in step.
--
-- Neither column is part of the hashed payload (V7 argues why), so filling them cannot invalidate a
-- chain, and this UPDATE is not a rewrite of any row's evidence.
--
-- C-3 回填：把两列出现之前写下的行，补上写入侧本来会给它们的值。
--
-- `V7__audit_row_outcome_and_tool` 以普通可空列加了 `outcome` 与 `tool`、什么都没填，故更早的每一行
-- 两列都是 NULL。一个改按列读的读取方于是不只是显示空白——它会报出**与被它取代的那种自由文本读法不同的
-- 率**，而那次切换本该不动这个。
--
-- 下面的规则就是写入侧自己的（`GovernedExecutionEngine`、`SideEffectService`），读自它们写的那些串。
-- 与 TypeScript 侧不同（那边有两种形状事后分不开），这里**全都推得出来**：本运行时的 `detail` 用词把
-- 整个结局说全了，而它也没有别的列可承载——本表**没有**结构化的拒绝理由列，只有那句话。
--
-- **只动 `outcome IS NULL` 的行。** 当前写入方产出的行两列都已有值，再写一遍只会丢信息。那条自由文本
-- 规则有**一处刻意比这一列粗**：`blocked` 与 `refused` 是两道闸、各一个词，而「句子里出现过 refused
-- 就匹配」的读法把两者折成了一个。现在从句子把列填上，正是为了让那个折法**活不过**这次切换。
--
-- 工具名是 `detail` 的第一个词。`POSITION` 与 `SUBSTRING … FROM … FOR` 都是标准 SQL，三种方言都原样
-- 实现，故本文件没有需要同步的方言孪生。
--
-- 两列都不进哈希载荷（理由见 V7），故填它们**不会**让任何一条链失效，这次 UPDATE 也不是对任何一行证据
-- 的改写。
UPDATE ai_audit_logs
SET tool = CASE
        WHEN action IN ('tool_call', 'tool_confirmation', 'effect_revoke')
             AND POSITION(' ' IN detail) > 0
            THEN SUBSTRING(detail FROM 1 FOR POSITION(' ' IN detail) - 1)
        ELSE NULL
    END,
    outcome = CASE
        WHEN action = 'tool_call' AND detail LIKE '% -> ok%' THEN 'executed'
        WHEN action = 'tool_call' AND detail LIKE '% -> fail:%' THEN 'failed'
        WHEN action = 'tool_call' AND detail LIKE '% refused:%' THEN 'refused'
        WHEN action = 'tool_call' AND detail LIKE '% blocked (risk policy)' THEN 'blocked'
        WHEN action = 'tool_call' AND detail LIKE '% pending confirmation' THEN 'pending'
        WHEN action = 'tool_call' AND detail LIKE '% awaiting a second person' THEN 'pending'
        WHEN action = 'tool_call' AND detail LIKE '% attempted' THEN 'attempted'
        WHEN action = 'tool_confirmation' AND detail LIKE '% approved (%' THEN 'approved'
        WHEN action = 'tool_confirmation' AND detail LIKE '% declined (%' THEN 'declined'
        WHEN action = 'tool_confirmation' AND detail LIKE '% execution retried' THEN 'attempted'
        WHEN action = 'effect_revoke' AND detail LIKE '% revoked (effect %' THEN 'revoked'
        ELSE NULL
    END
WHERE outcome IS NULL;
