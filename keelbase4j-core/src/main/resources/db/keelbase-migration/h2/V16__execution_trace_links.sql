-- The host's trace id for one governed attempt, keyed by the execution id the runtime already writes
-- on its audit and effect rows. One row per attempt; the audit chain and the host's own operation log
-- keep their shapes.
-- 一次受治理尝试对应的宿主 trace id，以运行时已写在审计行与副作用行上的 execution id 为键。每次尝试一行；
-- 审计链与宿主自己的操作日志各保持原形。
CREATE TABLE execution_trace_links (
    execution_id VARCHAR(64) NOT NULL,
    trace_id VARCHAR(64),
    tool VARCHAR(255),
    user_id VARCHAR(64),
    conversation_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (execution_id)
);

CREATE INDEX idx_execution_trace_links_trace_id ON execution_trace_links (trace_id);
