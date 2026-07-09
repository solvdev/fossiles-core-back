-- Estado intermedio CONTADO: conteo fisico terminado, vitrinas bloqueadas, pendiente de revision.
-- Ejecutar en PostgreSQL antes de usar "Terminar conteo fisico" (ddl-auto=validate).

ALTER TABLE kiosco_physical_count
    DROP CONSTRAINT IF EXISTS chk_kiosco_physical_count_status;

ALTER TABLE kiosco_physical_count
    ADD CONSTRAINT chk_kiosco_physical_count_status
        CHECK (status IN ('DRAFT', 'CONTADO', 'REVISADO', 'CERRADO'));

COMMENT ON COLUMN kiosco_physical_count.status IS
    'DRAFT=editable, CONTADO=vitrinas bloqueadas, REVISADO=revisado por supervisor, CERRADO=cerrado definitivo';
