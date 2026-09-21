ALTER TABLE platform_automation_trigger_occurrences DROP CONSTRAINT ck_automation_occurrence_state;
ALTER TABLE platform_automation_trigger_occurrences ADD CONSTRAINT ck_automation_occurrence_state CHECK(state IN ('RECEIVED','READY','DISPATCHING','BLOCKED','DISPATCHED','SUCCEEDED','FAILED','UNKNOWN','DEAD_LETTERED'));
CREATE INDEX idx_automation_occurrence_dispatching ON platform_automation_trigger_occurrences(updated_at,id) WHERE state='DISPATCHING';
