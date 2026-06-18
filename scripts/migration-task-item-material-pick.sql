-- Run manually against PostgreSQL when deploying (JPA ddl-auto=validate).
-- Tracks per-BOM-line material preparation for task items (materials delivery workflow).

CREATE TABLE IF NOT EXISTS task_item_material_pick (
    id              BIGSERIAL PRIMARY KEY,
    task_item_id    BIGINT NOT NULL REFERENCES task_item (id) ON DELETE CASCADE,
    material_id     BIGINT NOT NULL,
    picked          BOOLEAN NOT NULL DEFAULT FALSE,
    picked_at       TIMESTAMP,
    picked_by       BIGINT,
    CONSTRAINT uq_task_item_material_pick UNIQUE (task_item_id, material_id)
);

CREATE INDEX IF NOT EXISTS idx_task_item_material_pick_task_item
    ON task_item_material_pick (task_item_id);
