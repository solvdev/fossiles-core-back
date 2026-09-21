-- Unidades por tarea (auto-reparto). Null = el generador usa 2.
ALTER TABLE product
    ADD COLUMN IF NOT EXISTS units_per_task INTEGER;
