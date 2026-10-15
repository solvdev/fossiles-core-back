-- REVERSA de scripts/update-ventas-sin-factura-a-salida-entrecueros.sql:
-- deja como VENTA los movimientos SALIDA que son ventas POS sin factura ('Venta sin factura ...').
--
-- Usalo si corriste el UPDATE y el back que esta en servicio todavia es el viejo (falla con
-- "No enum constant ... KioscoMovementType.SALIDA"). Cuando despliegues el back nuevo, vuelve a correr el UPDATE.
-- No cambia cantidades ni stock. kiosco_movement es append-only: UN SOLO script, BEGIN ... COMMIT.

-- Preview: debe coincidir con lo que convirtio el UPDATE
SELECT COUNT(*) AS movimientos_a_revertir, COALESCE(SUM(quantity), 0) AS unidades
FROM kiosco_movement
WHERE movement_type = 'SALIDA'
  AND reason LIKE 'Venta sin factura%';

BEGIN;

-- La funcion del trigger append-only devolvia OLD tambien en UPDATE y eso anula el cambio ("UPDATE n" sin
-- modificar la fila). Con el flag de admin debe devolver NEW (idempotente).
CREATE OR REPLACE FUNCTION prevent_kiosco_movement_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF COALESCE(
            current_setting('app.kiosco_movement_admin_mutation', true),
            'false'
        ) = 'true' THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'kiosco_movement is append-only; UPDATE/DELETE are not allowed';
END;
$$ LANGUAGE plpgsql;

SELECT set_config('app.kiosco_movement_admin_mutation', 'true', true);

UPDATE kiosco_movement
SET movement_type = 'VENTA',
    reason = NULL
WHERE movement_type = 'SALIDA'
  AND reason LIKE 'Venta sin factura%';

SELECT set_config('app.kiosco_movement_admin_mutation', 'false', true);

-- Verificacion: no debe quedar ninguna SALIDA (si habia otras SALIDA que no son ventas sin factura, siguen aqui)
SELECT movement_type, COUNT(*) AS movimientos
FROM kiosco_movement
WHERE movement_type = 'SALIDA'
GROUP BY movement_type;

-- Si algo no cuadra: ROLLBACK;   Si todo bien:
COMMIT;
