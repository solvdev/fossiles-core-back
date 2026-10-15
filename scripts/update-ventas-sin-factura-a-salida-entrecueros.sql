-- Ventas POS de ENTRECUEROS SIN FACTURA -> movimiento SALIDA (Sal.) en vez de VENTA (Vtas.).
-- Las que SI tienen factura se quedan (o vuelven a ser) VENTA.
--
-- Una venta de Entrecueros sin factura queda con kiosk_sale.fel_status = 'SKIPPED'. El back nuevo ya las registra
-- como movimiento SALIDA con motivo "Venta sin factura POS-AAAAMMDD-NNNN" y, si despues se factura (correccion
-- manual de datos FEL o reintento), las devuelve a VENTA. Este script hace lo mismo con las que ya existen.
--
-- "SIN FACTURA" = fel_status SKIPPED y NINGUNA evidencia de factura:
--   * la venta no tiene UUID, serie, numero ni fecha de certificacion FEL, y
--   * ninguna tax_invoice ligada (por source_id o por kiosk_sale.invoice_id) esta CERTIFIED o trae UUID/serie/numero.
-- Una venta SKIPPED que si tenga alguno de esos datos se considera FACTURADA: sigue siendo VENTA (el preview las
-- lista aparte) y, si ya estaba como SALIDA, este script la devuelve a VENTA.
--
-- QUE CAMBIA:    kiosco_movement.movement_type VENTA -> SALIDA y reason = 'Venta sin factura <No. venta>'
--                (y SALIDA -> VENTA, reason NULL, para las que resultan facturadas).
-- QUE NO CAMBIA: cantidades, stock_before/stock_after ni el stock actual (los dos tipos restan igual).
--                Las ANULACION de esas ventas quedan como estan.
-- EFECTO:        kardex y conteo fisico cuentan las sin factura en "Sal." y no en "Vtas."; el panel de movimientos
--                las muestra como "Salida".
--
-- Solo se tocan movimientos cuyo reference_id es una venta POS (kiosk_sale) DEL MISMO kiosko que el stock del
-- movimiento, kiosko Entrecueros (id 42 o locations.pos_mode = 'ENTRECUEROS'). Las VENTA con boleta fisica
-- (boleta de cambio) no se convierten.
--
-- Es re-ejecutable: una segunda corrida ya no encuentra nada que cambiar.
-- ORDEN (importante):
--   1) scripts/migration-kiosco-movement-salida.sql  (permite el tipo SALIDA en la tabla)
--   2) DESPLEGAR el back nuevo (un back viejo no conoce SALIDA y falla al leer esas filas:
--      "No enum constant ... KioscoMovementType.SALIDA")
--   3) recien despues este script.
-- Si ya lo corriste con el back viejo en servicio, desplegar el back nuevo lo resuelve; si no puedes desplegar
-- todavia, usa scripts/revert-salida-ventas-sin-factura-a-venta.sql para dejarlo como estaba.
-- Correr TODO en una sola sesion (usa una tabla temporal), con BEGIN ... COMMIT al final.

-- ---------------------------------------------------------------------------
-- 0) Ventas de Entrecueros SIN ninguna evidencia de factura (tabla temporal de la sesion)
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS tmp_ventas_sin_factura;
CREATE TEMP TABLE tmp_ventas_sin_factura AS
SELECT ks.id AS sale_id, ks.sale_number, ks.kiosk_location_id
FROM kiosk_sale ks
JOIN locations l ON l.id = ks.kiosk_location_id
WHERE (l.id = 42 OR UPPER(COALESCE(l.pos_mode, '')) = 'ENTRECUEROS')
  AND UPPER(TRIM(COALESCE(ks.fel_status, ''))) = 'SKIPPED'
  AND COALESCE(TRIM(ks.fel_uuid), '') = ''
  AND COALESCE(TRIM(ks.fel_serie), '') = ''
  AND COALESCE(TRIM(ks.fel_numero), '') = ''
  AND ks.fel_certified_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM tax_invoice ti
      WHERE ((ti.source_type = 'KIOSK_SALE' AND ti.source_id = ks.id) OR ti.id = ks.invoice_id)
        AND (UPPER(TRIM(COALESCE(ti.status, ''))) = 'CERTIFIED'
             OR COALESCE(TRIM(ti.fel_uuid), '') <> ''
             OR COALESCE(TRIM(ti.fel_serie), '') <> ''
             OR COALESCE(TRIM(ti.fel_numero), '') <> ''
             OR ti.fel_certified_at IS NOT NULL)
  );

-- ---------------------------------------------------------------------------
-- 1) PREVIEW (solo lectura)
-- ---------------------------------------------------------------------------
-- 1a) Lo que se va a convertir a SALIDA, por venta
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
JOIN tmp_ventas_sin_factura v ON v.sale_id = m.reference_id AND v.kiosk_location_id = st.location_id
JOIN kiosk_sale ks ON ks.id = v.sale_id
JOIN locations l ON l.id = ks.kiosk_location_id
WHERE (m.movement_type = 'VENTA' AND m.physical_slip_number IS NULL)
   OR (m.movement_type = 'DEVOLUCION_A_CLIENTE' AND m.reason LIKE 'Venta sin factura%')
GROUP BY ks.id, ks.sale_number, ks.sale_date, ks.status, l.name
ORDER BY ks.sale_date, ks.id;

-- 1b) Ventas SKIPPED de Entrecueros que SI tienen datos de factura: se quedan como VENTA
SELECT
    ks.id AS sale_id, ks.sale_number, ks.sale_date, ks.fel_status,
    ks.fel_uuid, ks.fel_serie, ks.fel_numero, ks.fel_certified_at,
    (SELECT string_agg(ti.status || ' ' || COALESCE(ti.fel_serie, '') || '-' || COALESCE(ti.fel_numero, ''), ', ')
       FROM tax_invoice ti
      WHERE (ti.source_type = 'KIOSK_SALE' AND ti.source_id = ks.id) OR ti.id = ks.invoice_id) AS facturas
FROM kiosk_sale ks
JOIN locations l ON l.id = ks.kiosk_location_id
WHERE (l.id = 42 OR UPPER(COALESCE(l.pos_mode, '')) = 'ENTRECUEROS')
  AND UPPER(TRIM(COALESCE(ks.fel_status, ''))) = 'SKIPPED'
  AND ks.id NOT IN (SELECT sale_id FROM tmp_ventas_sin_factura)
ORDER BY ks.sale_date, ks.id;

-- 1c) Movimientos SALIDA 'Venta sin factura' de ventas que resultan FACTURADAS: vuelven a VENTA
SELECT m.id AS movement_id, m.reference_id AS sale_id, m.quantity, m.reason
FROM kiosco_movement m
JOIN kiosco_stock st ON st.id = m.kiosco_stock_id
WHERE m.movement_type = 'SALIDA'
  AND m.reason LIKE 'Venta sin factura%'
  AND NOT EXISTS (
      SELECT 1 FROM tmp_ventas_sin_factura v
      WHERE v.sale_id = m.reference_id AND v.kiosk_location_id = st.location_id
  )
ORDER BY m.id;

-- Resumen
SELECT
    (SELECT COUNT(*) FROM kiosco_movement m
       JOIN kiosco_stock st ON st.id = m.kiosco_stock_id
       JOIN tmp_ventas_sin_factura v ON v.sale_id = m.reference_id AND v.kiosk_location_id = st.location_id
      WHERE (m.movement_type = 'VENTA' AND m.physical_slip_number IS NULL)
         OR (m.movement_type = 'DEVOLUCION_A_CLIENTE' AND m.reason LIKE 'Venta sin factura%')) AS a_convertir_a_salida,
    (SELECT COUNT(*) FROM kiosco_movement m
       JOIN kiosco_stock st ON st.id = m.kiosco_stock_id
      WHERE m.movement_type = 'SALIDA'
        AND m.reason LIKE 'Venta sin factura%'
        AND NOT EXISTS (SELECT 1 FROM tmp_ventas_sin_factura v
                         WHERE v.sale_id = m.reference_id AND v.kiosk_location_id = st.location_id)) AS a_devolver_a_venta;

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

-- Sin factura -> SALIDA
UPDATE kiosco_movement m
SET movement_type = 'SALIDA',
    reason = 'Venta sin factura ' || v.sale_number
FROM kiosco_stock st, tmp_ventas_sin_factura v
WHERE st.id = m.kiosco_stock_id
  AND v.sale_id = m.reference_id
  AND v.kiosk_location_id = st.location_id
  AND ((m.movement_type = 'VENTA' AND m.physical_slip_number IS NULL)
       OR (m.movement_type = 'DEVOLUCION_A_CLIENTE' AND m.reason LIKE 'Venta sin factura%'));

-- Facturadas que estaban como SALIDA -> VENTA
UPDATE kiosco_movement m
SET movement_type = 'VENTA',
    reason = NULL
FROM kiosco_stock st
WHERE st.id = m.kiosco_stock_id
  AND m.movement_type = 'SALIDA'
  AND m.reason LIKE 'Venta sin factura%'
  AND NOT EXISTS (
      SELECT 1 FROM tmp_ventas_sin_factura v
      WHERE v.sale_id = m.reference_id AND v.kiosk_location_id = st.location_id
  );

SELECT set_config('app.kiosco_movement_admin_mutation', 'false', true);

-- Verificacion 1: ya no debe quedar VENTA de ventas sin factura de Entrecueros (esperado: 0 filas)
SELECT m.id, m.reference_id, v.sale_number
FROM kiosco_movement m
JOIN kiosco_stock st ON st.id = m.kiosco_stock_id
JOIN tmp_ventas_sin_factura v ON v.sale_id = m.reference_id AND v.kiosk_location_id = st.location_id
WHERE m.movement_type = 'VENTA' AND m.physical_slip_number IS NULL;

-- Verificacion 2: ninguna venta CON factura (UUID/serie/numero/certificada) debe tener movimiento SALIDA (esperado: 0 filas)
SELECT m.id, m.reference_id, ks.sale_number, ks.fel_uuid, ks.fel_serie, ks.fel_numero
FROM kiosco_movement m
JOIN kiosco_stock st ON st.id = m.kiosco_stock_id
JOIN kiosk_sale ks ON ks.id = m.reference_id AND ks.kiosk_location_id = st.location_id
WHERE m.movement_type = 'SALIDA'
  AND (COALESCE(TRIM(ks.fel_uuid), '') <> '' OR COALESCE(TRIM(ks.fel_serie), '') <> ''
       OR COALESCE(TRIM(ks.fel_numero), '') <> '' OR ks.fel_certified_at IS NOT NULL
       OR UPPER(TRIM(COALESCE(ks.fel_status, ''))) = 'CERTIFIED');

-- Verificacion 3: movimientos SALIDA (ventas sin factura), por kiosko
SELECT l.name AS kiosko, COUNT(*) AS movimientos, SUM(m.quantity) AS unidades
FROM kiosco_movement m
JOIN kiosco_stock st ON st.id = m.kiosco_stock_id
JOIN locations l ON l.id = st.location_id
WHERE m.movement_type = 'SALIDA'
GROUP BY l.name
ORDER BY l.name;

-- Si algo no cuadra: ROLLBACK;   Si todo bien:
COMMIT;
