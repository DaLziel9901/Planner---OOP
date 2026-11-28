-- Apply db/bootstrap/app-user.sql first in the same guarded transaction.
ALTER TABLE plan
    ADD COLUMN owner_user_id BIGINT,
    ADD CONSTRAINT plan_owner_fk FOREIGN KEY (owner_user_id) REFERENCES app_user(id) ON DELETE RESTRICT,
    ADD CONSTRAINT plan_owner_required CHECK (owner_user_id IS NOT NULL) NOT VALID;

ALTER TABLE task
    ADD COLUMN owner_user_id BIGINT,
    ADD CONSTRAINT task_owner_fk FOREIGN KEY (owner_user_id) REFERENCES app_user(id) ON DELETE RESTRICT,
    ADD CONSTRAINT task_owner_scope CHECK (
        (plan_id IS NULL AND sub_plan_id IS NULL AND owner_user_id IS NOT NULL)
        OR ((plan_id IS NOT NULL OR sub_plan_id IS NOT NULL) AND owner_user_id IS NULL)
    ) NOT VALID;

CREATE INDEX plan_owner_idx ON plan (owner_user_id, id);
CREATE INDEX sub_plan_order_idx ON sub_plan (plan_id, order_index, id);
CREATE INDEX sub_plan_parent_idx ON sub_plan (parent_sub_plan_id, plan_id);
CREATE INDEX task_owner_idx ON task (owner_user_id, order_index, id);
CREATE INDEX task_plan_idx ON task (plan_id, order_index, id);
CREATE INDEX task_sub_plan_idx ON task (sub_plan_id, order_index, id);
CREATE INDEX sub_task_task_idx ON sub_task (task_id, order_index, id);
