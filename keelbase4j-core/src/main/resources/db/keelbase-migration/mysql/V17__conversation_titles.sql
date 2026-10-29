-- A conversation's name, as the user gave it.
--
-- Conversations here are derived from their messages — there is no conversation row — so this is the
-- one fact about a conversation that has no message to live on. The contract's `conversation-data` v2
-- carries it as an optional `title`; a conversation with no row here simply has none.
--
-- 会话的名字，由用户给出。
--
-- 这里的会话是由消息推出来的——没有会话行——故这是「关于会话」而**没有一条消息可依附**的唯一事实。
-- 契约的 `conversation-data` v2 把它作为可选的 `title` 带出；这里没有行，就只是没有名字。

CREATE TABLE conversation_titles (
    conversation_id VARCHAR(255) PRIMARY KEY,
    user_id         VARCHAR(255) NOT NULL,
    title           VARCHAR(200) NOT NULL,
    updated_at      DATETIME(6) NOT NULL
);
