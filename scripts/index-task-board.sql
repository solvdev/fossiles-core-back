-- Índice opcional para GET /api/tasks/board (Tablero del día).
-- NO es requisito para desplegar: el back arranca y el endpoint funciona sin él; solo acelera la
-- consulta cuando la tabla task crece. Solo agrega un índice: no cambia datos ni columnas.
-- Conviene correrlo primero en fosstest y medir (EXPLAIN ANALYZE) antes y después.
--
-- CONCURRENTLY: no bloquea escrituras sobre task mientras se crea (no puede ir dentro de una
-- transacción; ejecútelo como sentencia suelta en psql).

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_task_board_active
    ON task (scheduled_date, desk)
    WHERE status NOT IN ('CANCELLED', 'COMPLETED');

-- Rollback: DROP INDEX CONCURRENTLY IF EXISTS idx_task_board_active;
