-- Permite DELETE/UPDATE en kiosco_movement solo con flag de sesion de administrador.
-- Ejecutar manualmente en PostgreSQL (produccion/staging) antes de borrar duplicados.
--
-- Uso manual (psql / pgAdmin), siempre dentro de una transaccion:
--
--   BEGIN;
--   SELECT set_config('app.kiosco_movement_admin_mutation', 'true', true);
--   DELETE FROM kiosco_movement WHERE id = 12345;
--   -- Opcional: recalcular stock del producto afectado en kiosco_stock
--   COMMIT;
--
-- Sin el flag, DELETE y UPDATE siguen bloqueados (append-only por defecto).

CREATE OR REPLACE FUNCTION prevent_kiosco_movement_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF COALESCE(
            current_setting('app.kiosco_movement_admin_mutation', true),
            'false'
        ) = 'true' THEN
        RETURN OLD;
    END IF;

    RAISE EXCEPTION 'kiosco_movement is append-only; UPDATE/DELETE are not allowed';
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION prevent_kiosco_movement_mutation() IS
    'Bloquea UPDATE/DELETE en kiosco_movement salvo cuando app.kiosco_movement_admin_mutation = true en la sesion.';

COMMENT ON TABLE kiosco_movement IS
    'Log de movimientos de inventario kiosko. Append-only por defecto; admin puede mutar con app.kiosco_movement_admin_mutation.';
