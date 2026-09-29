-- Approval mode: a high-impact action waits for a second person (R4, JV-33 ③).
--
-- `mode` records which confirmation a row *is*, rather than inferring it from the risk level: the two
-- are not synonyms — a policy can promote an R3 tool to approval, and the mode is what decides who may
-- answer it. `immediate` is the default so every pre-existing row keeps meaning what it meant.
--
-- `operator_identity` carries the initiator's identity with the row. An approval row outlives the
-- request that created it (R4's waiting window is hours to days), so the write has to be performed *as
-- the initiator* when somebody else approves it — and by then the initiator is not the caller. The
-- identity is stored in its wire form; see `OperatorIdentity`.
--
-- `approver_id` records who answered an approval row. For an immediate row the decider is the
-- operator and this stays null.
--
-- This file is immutable once applied: a change to the schema is a new V<n>__ file.
ALTER TABLE confirmation_requests ADD COLUMN mode VARCHAR(255) NOT NULL DEFAULT 'immediate';
ALTER TABLE confirmation_requests ADD COLUMN approver_id VARCHAR(255);
ALTER TABLE confirmation_requests ADD COLUMN operator_identity VARCHAR(4000);
