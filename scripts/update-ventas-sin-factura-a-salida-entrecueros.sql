-- Ventas POS de ENTRECUEROS SIN FACTURA -> movimiento SALIDA (Sal.) en vez de VENTA (Vtas.).
--
-- Una venta de Entrecueros sin factura queda con kiosk_sale.fel_status = 'SKIPPED' (no se puede facturar
-- despues). El back nuevo ya las registra como movimiento SALIDA con motivo "Venta sin factura POS-AAAAMMDD-NNNN";
-- este script hace lo mismo con las que ya existen.
--
-- QUE CAMBIA:    kiosco_movement.movement_type VENTA -> SALIDA y reason = 'Venta sin factura <No. venta>'.
-- QUE NO CAMBIA: cantidades, stock_before/stock_after ni el stock actual (los dos tipos restan igual).
--                Las ANULACION de esas ventas quedan como estan.
-- EFECTO:        kardex y conteo fisico las cuentan en "Sal." y ya no en "Vtas."; el panel de movimientos las
--                muestra como "Salida".
--
-- CRITERIO (un movimiento se convierte solo si cumple TODO):
--   * movement_type = 'VENTA', sin numero de boleta fisica (las de boleta de cambio no se tocan)
--   * reference_id es una venta POS (kiosk_sale) DEL MISMO kiosko que el stock del movimiento
--   * el kiosko es Entrecueros (id 42 o locations.pos_mode = 'ENTRECUEROS')
--   * la venta tiene fel_status = 'SKIPPED', sin UUID FEL y sin factura certificada en tax_invoice
-- Ademas, si ya habias corrido una version anterior de este script (que las dejo como DEVOLUCION_A_CLIENTE con
-- motivo 'Venta sin factura ...'), esas filas tambien pasan a SALIDA.
--
-- Es re-ejecutable: una segunda corrida ya no encuentra nada que convertir.
-- ORDEN: primero scripts/migration-kiosco-movement-salida.sql (permite el tipo SALIDA en la tabla).
-- kiosco_movement es append-only: el UPDATE necesita el flag de sesion (UN SOLO script: BEGIN ... COMMIT).

-- ---------------------------------------------------------------------------
-- 1) PREVIEW (solo lectura): lo que se va a convertir, por venta
-- ---------------------------------------------------------------------------
SELECT
    ks.id           AS sale_id,
    ks.sale_number,
    ks.sale_date,
    ks.status       AS sale_status,
    l.name          AS kiosko,
    COUNT(*)        AS movimientos,
    SUM(m.quantity) AS unidades
FROM kiosco_movement m
JOIN kiosco_stock st ON st.id = m.kiosco_stock_id
JOIN kiosk_sale ks ON ks.id = m.reference_id AND ks.kiosk_location_id = st.location_id
JOIN locations l ON l.id = ks.kiosk_location_id
WHERE (
        (m.movement_type = 'VENTA' AND m.physical_slip_number IS NULL)
        OR (m.movement_type = 'DEVOLUCION_A_CLIENTE' AND m.reason LIKE 'Venta sin factura%')
      )
  AND (l.id = 42 OR UPPER(COALESCE(l.pos_mode, '')) = 'ENTRECUEROS')
  AND UPPER(TRIM(COALESCE(ks.fel_status, ''))) = 'SKIPPED'
  AND COALESCE(TRIM(ks.fel_uuid), '') = ''
  AND NOT EXISTS (
      SELECT 1 FROM tax_invoice ti
      WHERE ti.source_type = 'KIOSK_SALE'
        AND ti.source_id = ks.id
        AND COALESCE(TRIM(ti.fel_uuid), '') <> ''
  )
GROUP BY ks.id, ks.sale_number, ks.sale_date, ks.status, l.name
ORDER BY ks.sale_date, ks.id;

-- Resumen del preview
SELECT COUNT(*) AS movimientos_a_convertir, COALESCE(SUM(m.quantity), 0) AS unidades
FROM kiosco_movement m
JOIN kiosco_stock st ON st.id = m.kiosco_stock_id
JOIN kiosk_sale ks ON ks.id = m.reference_id AND ks.kiosk_location_id = st.location_id
JOIN locations l ON l.id = ks.kiosk_location_id
WHERE (
        (m.movement_type = 'VENTA' AND m.physical_slip_number IS NULL)
        OR (m.movement_type = 'DEVOLUCION_A_CLIENTE' AND m.reason LIKE 'Venta sin factura%')
      )
  AND (l.id = 42 OR UPPER(COALESCE(l.pos_mode, '')) = 'ENTRECUEROS')
  AND UPPER(TRIM(COALESCE(ks.fel_status, ''))) = 'SKIPPED'
  AND COALESCE(TRIM(ks.fel_uuid), '') = ''
  AND NOT EXISTS (
      SELECT 1 FROM tax_invoice ti
      WHERE ti.source_type = 'KIOSK_SALE'
        AND ti.source_id = ks.id
        AND COALESCE(TRIM(ti.fel_uuid), '') <> ''
  );

-- ---------------------------------------------------------------------------
-- 2) UPDATE (transaccion). Revisa los conteos de "verificacion" antes del COMMIT.
-- ---------------------------------------------------------------------------
BEGIN;

-- Paso 0a: permitir el tipo SALIDA en la tabla (igual que scripts/migration-kiosco-movement-salida.sql;
-- idempotente, no hace falta si ya la corriste).
ALTER TABLE kiosco_movement
    DROP CONSTRAINT IF EXISTS chk_kiosco_movement_type;

ALTER TABLE kiosco_movement
    ADD CONSTRAINT chk_kiosco_movement_type CHECK (
        movement_type IN (
            'ENTRADA',
            'VENTA',
            'DEVOLUCION_DEPOSITO',
            'DEVOLUCION_CLIENTE',
            'DEVOLUCION_A_CLIENTE',
            'SALIDA',
            'TRASLADO_SALIDA',
            'TRASLADO_ENTRADA',
            'MERMA',
            'AJUSTE',
            'ANULACION',
            'CAMBIO'
        )
    );

-- Paso 0b: la funcion del trigger append-only devolvia OLD tambien en UPDATE, y en PostgreSQL eso hace que el
-- UPDATE responda "UPDATE n" pero deje la fila SIN cambios. Con el flag de admin debe devolver NEW.
-- Es idempotente y sigue bloqueando UPDATE/DELETE cuando el flag no esta activo.
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

UPDATE kiosco_movement m
SET movement_type = 'SALIDA',
    reason = 'Venta sin factura ' || ks.sale_number
FROM kiosco_stock st, kiosk_sale ks, locations l
WHERE st.id = m.kiosco_stock_id
  AND ks.id = m.reference_id
  AND ks.kiosk_location_id = st.location_id
  AND l.id = ks.kiosk_location_id
  AND (
        (m.movement_type = 'VENTA' AND m.physical_slip_number IS NULL)
        OR (m.movement_type = 'DEVOLUCION_A_CLIENTE' AND m.reason LIKE 'Venta sin factura%')
      )
  AND (l.id = 42 OR UPPER(COALESCE(l.pos_mode, '')) = 'ENTRECUEROS')
  AND UPPER(TRIM(COALESCE(ks.fel_status, ''))) = 'SKIPPED'
  AND COALESCE(TRIM(ks.fel_uuid), '') = ''
  AND NOT EXISTS (
      SELECT 1 FROM tax_invoice ti
      WHERE ti.source_type = 'KIOSK_SALE'
        AND ti.source_id = ks.id
        AND COALESCE(TRIM(ti.fel_uuid), '') <> ''
  );

SELECT set_config('app.kiosco_movement_admin_mutation', 'false', true);

-- Verificacion 1: ya no debe quedar VENTA de ventas sin factura de Entrecueros (esperado: 0 filas)
SELECT m.id, m.reference_id, ks.sale_number
FROM kiosco_movement m
JOIN kiosco_stock st ON st.id = m.kiosco_stock_id
JOIN kiosk_sale ks ON ks.id = m.reference_id AND ks.kiosk_location_id = st.location_id
JOIN locations l ON l.id = ks.kiosk_location_id
WHERE m.movement_type = 'VENTA'
  AND m.physical_slip_number IS NULL
  AND (l.id = 42 OR UPPER(COALESCE(l.pos_mode, '')) = 'ENTRECUEROS')
  AND UPPER(TRIM(COALESCE(ks.fel_status, ''))) = 'SKIPPED'
  AND COALESCE(TRIM(ks.fel_uuid), '') = '';

-- Verificacion 2: movimientos SALIDA (ventas sin factura), por kiosko
SELECT l.name AS kiosko, COUNT(*) AS movimientos, SUM(m.quantity) AS unidades
FROM kiosco_movement m
JOIN kiosco_stock st ON st.id = m.kiosco_stock_id
JOIN locations l ON l.id = st.location_id
WHERE m.movement_type = 'SALIDA'
GROUP BY l.name
ORDER BY l.name;

-- Si algo no cuadra: ROLLBACK;   Si todo bien:
COMMIT;
