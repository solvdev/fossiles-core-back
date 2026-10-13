-- =====================================================================
-- Revertir un CAMBIO de venta en línea creado por error: ENVL-1604
-- =====================================================================
-- Qué deshace (todo lo que generó createExchange + la orden de producción):
--   1) Orden de producción creada desde el envío (items, tareas, unidades de bodega PT...).
--      Si la OP trae items de OTRAS ventas, NO se borra la OP: solo se quitan los de ENVL-1604.
--   2) La venta/envío ENVL-1604 y sus items.
--   3) El ingreso a Devoluciones que hizo el cambio: online_sale_return (+ líneas),
--      return_inventory, kardex RETURN y el stock de product_inventory_location.
--   4) La nota "CAMBIO: líneas regresadas a devoluciones" en las observaciones de la venta original.
--
-- USO
--   * Postgres. Correr PRIMERO el bloque 0 (solo lectura) y revisar lo que sale.
--   * El bloque 1 corre dentro de BEGIN ... y termina en ROLLBACK (no cambia nada).
--     Si el resumen final se ve bien, cambiar ROLLBACK por COMMIT y volver a correr TODO el bloque 1.
--   * Si algo no cuadra el script lanza EXCEPTION y no se aplica nada.
--   * Hacer respaldo de la BD antes del COMMIT.
-- =====================================================================


-- =====================================================================
-- 0) DIAGNÓSTICO (solo lectura)
-- =====================================================================

-- 0.1 Envío a revertir y su venta original (ENVIO_REL en observaciones)
SELECT s.id, s.sale_number, s.shipment_number, s.status, s.sale_date, s.created_at,
       s.total_amount, s.in_production_order, s.production_order_id, s.invoice_id,
       substring(s.observations from 'CAMBIO_Q0\(ENVIO_REL=([^)]*)\)') AS original_shipment
FROM online_sale s
WHERE s.shipment_number = 'ENVL-1604';

-- 0.2 Items del envío
SELECT i.* FROM online_sale_item i
JOIN online_sale s ON s.id = i.online_sale_id
WHERE s.shipment_number = 'ENVL-1604';

-- 0.3 Órdenes de producción que tocan el envío y qué otras ventas traen
SELECT poi.production_order_id, po.status AS po_status,
       count(*) FILTER (WHERE poi.online_sale_id = s.id)  AS items_envl_1604,
       count(*) FILTER (WHERE poi.online_sale_id IS DISTINCT FROM s.id) AS items_otras_ventas
FROM online_sale s
JOIN production_order_item poi0 ON poi0.online_sale_id = s.id
JOIN production_order po ON po.id = poi0.production_order_id
JOIN production_order_item poi ON poi.production_order_id = po.id
WHERE s.shipment_number = 'ENVL-1604'
GROUP BY poi.production_order_id, po.status;

-- 0.4 Registros de devolución que creó el cambio (ventana ±10 min de la creación del envío)
WITH ex AS (
  SELECT s.id, s.created_at,
         (SELECT o.id FROM online_sale o
           WHERE o.shipment_number = substring(s.observations from 'CAMBIO_Q0\(ENVIO_REL=([^)]*)\)')
           ORDER BY o.id LIMIT 1) AS orig_id
  FROM online_sale s WHERE s.shipment_number = 'ENVL-1604')
SELECT 'online_sale_return' AS tabla, r.id, r.online_sale_id, r.return_reason, r.created_at
FROM online_sale_return r, ex
WHERE r.online_sale_id = ex.orig_id AND r.return_reason ILIKE 'CAMBIO%'
  AND r.created_at BETWEEN ex.created_at - INTERVAL '10 minutes' AND ex.created_at + INTERVAL '10 minutes'
UNION ALL
SELECT 'return_inventory', ri.id, ri.online_sale_id, ri.return_reason, ri.created_at
FROM return_inventory ri, ex
WHERE ri.online_sale_id = ex.orig_id AND ri.return_reason ILIKE 'CAMBIO%'
  AND ri.created_at BETWEEN ex.created_at - INTERVAL '10 minutes' AND ex.created_at + INTERVAL '10 minutes'
UNION ALL
SELECT 'kardex RETURN', k.id, k.reference_id, k.product_id || ' / ' || coalesce(k.size_label,'') || ' / qty ' || k.quantity, k.created_at
FROM product_inventory_kardex k, ex
WHERE k.reference_type = 'ONLINE_SALE_RETURN' AND k.reference_id = ex.orig_id
  AND k.movement_type = 'RETURN'
  AND k.created_at BETWEEN ex.created_at - INTERVAL '10 minutes' AND ex.created_at + INTERVAL '10 minutes';


-- =====================================================================
-- 1) REVERSIÓN (transacción)
-- =====================================================================
BEGIN;

CREATE TEMP TABLE _ex ON COMMIT DROP AS
SELECT s.id AS sale_id, s.created_at,
       (SELECT o.id FROM online_sale o
         WHERE o.shipment_number = substring(s.observations from 'CAMBIO_Q0\(ENVIO_REL=([^)]*)\)')
         ORDER BY o.id LIMIT 1) AS orig_id
FROM online_sale s
WHERE s.shipment_number = 'ENVL-1604';

CREATE TEMP TABLE _po ON COMMIT DROP AS
SELECT DISTINCT poi.production_order_id AS po_id
FROM production_order_item poi JOIN _ex ON poi.online_sale_id = _ex.sale_id;

DO $$
DECLARE
  n int; st text; inv bigint;
BEGIN
  SELECT count(*) INTO n FROM _ex;
  IF n <> 1 THEN RAISE EXCEPTION 'Se esperaba 1 envío ENVL-1604, se encontraron %', n; END IF;

  SELECT s.status, s.invoice_id INTO st, inv FROM online_sale s JOIN _ex ON _ex.sale_id = s.id;
  IF st IN ('ENVIADO','ENTREGADO') THEN
    RAISE EXCEPTION 'ENVL-1604 está % (ya despachado): revertir primero el despacho', st;
  END IF;
  IF inv IS NOT NULL THEN
    RAISE EXCEPTION 'ENVL-1604 tiene factura (invoice_id=%): anularla antes', inv;
  END IF;
  IF (SELECT orig_id FROM _ex) IS NULL THEN
    RAISE EXCEPTION 'No se pudo identificar la venta original (ENVIO_REL) del cambio';
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- 1.1 Orden(es) de producción
--     OPs que quedarán SIN items de otras ventas -> se borran completas.
--     OPs compartidas -> solo se quitan los items de ENVL-1604.
-- ---------------------------------------------------------------------
CREATE TEMP TABLE _po_full ON COMMIT DROP AS
SELECT po_id FROM _po
WHERE NOT EXISTS (
  SELECT 1 FROM production_order_item x
  WHERE x.production_order_id = _po.po_id
    AND x.online_sale_id IS DISTINCT FROM (SELECT sale_id FROM _ex));

CREATE TEMP TABLE _poi ON COMMIT DROP AS
SELECT poi.id FROM production_order_item poi
WHERE poi.online_sale_id = (SELECT sale_id FROM _ex);

-- Bloqueos: si la OP ya tiene movimientos contables/despachos, no se borra automáticamente
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT po_id FROM _po_full LOOP
    IF EXISTS (SELECT 1 FROM customer_account_entry WHERE production_order_id = r.po_id) THEN
      RAISE EXCEPTION 'OP % tiene movimientos en customer_account_entry', r.po_id; END IF;
    IF EXISTS (SELECT 1 FROM product_shipment WHERE production_order_id = r.po_id) THEN
      RAISE EXCEPTION 'OP % tiene product_shipment', r.po_id; END IF;
    IF EXISTS (SELECT 1 FROM internal_shipment_request WHERE production_order_id = r.po_id) THEN
      RAISE EXCEPTION 'OP % tiene internal_shipment_request', r.po_id; END IF;
    IF EXISTS (SELECT 1 FROM leather_movement WHERE production_order_id = r.po_id) THEN
      RAISE EXCEPTION 'OP % tiene leather_movement', r.po_id; END IF;
    IF EXISTS (SELECT 1 FROM material_consumption WHERE production_order_id = r.po_id) THEN
      RAISE EXCEPTION 'OP % tiene material_consumption', r.po_id; END IF;
    IF EXISTS (SELECT 1 FROM production_order_warehouse_unit
               WHERE production_order_id = r.po_id AND receipt_status <> 'PENDING') THEN
      RAISE EXCEPTION 'OP % ya tiene unidades recibidas/enviadas en bodega PT', r.po_id; END IF;
  END LOOP;
END $$;

-- Hijos de los items de ENVL-1604
DELETE FROM production_order_warehouse_unit WHERE production_order_item_id IN (SELECT id FROM _poi);
DELETE FROM production_order_partial_release_line WHERE production_order_item_id IN (SELECT id FROM _poi);
DELETE FROM production_cincho_day_status WHERE production_order_item_id IN (SELECT id FROM _poi);
DELETE FROM task_item WHERE production_order_item_id IN (SELECT id FROM _poi);

-- Hijos de las OP completas
DELETE FROM qa_record WHERE production_order_id IN (SELECT po_id FROM _po_full)
                         OR task_id IN (SELECT id FROM task WHERE production_order_id IN (SELECT po_id FROM _po_full));
DELETE FROM task_item WHERE task_id IN (SELECT id FROM task WHERE production_order_id IN (SELECT po_id FROM _po_full));
DELETE FROM task WHERE production_order_id IN (SELECT po_id FROM _po_full);
DELETE FROM production_order_partial_release_line
 WHERE release_id IN (SELECT id FROM production_order_partial_release WHERE production_order_id IN (SELECT po_id FROM _po_full));
DELETE FROM production_order_partial_release WHERE production_order_id IN (SELECT po_id FROM _po_full);
DELETE FROM production_order_warehouse_unit WHERE production_order_id IN (SELECT po_id FROM _po_full);
DELETE FROM production_cincho_day_status WHERE production_order_id IN (SELECT po_id FROM _po_full);

-- Items y órdenes
DELETE FROM production_order_item WHERE id IN (SELECT id FROM _poi);
DELETE FROM production_order WHERE id IN (SELECT po_id FROM _po_full);

-- ---------------------------------------------------------------------
-- 1.2 Venta/envío ENVL-1604
-- ---------------------------------------------------------------------
DELETE FROM online_sale_item WHERE online_sale_id = (SELECT sale_id FROM _ex);
DELETE FROM online_sale WHERE id = (SELECT sale_id FROM _ex);

-- ---------------------------------------------------------------------
-- 1.3 Devoluciones que generó el cambio (misma ventana de ±10 min)
-- ---------------------------------------------------------------------
CREATE TEMP TABLE _ret ON COMMIT DROP AS
SELECT r.id FROM online_sale_return r, _ex
WHERE r.online_sale_id = _ex.orig_id AND r.return_reason ILIKE 'CAMBIO%'
  AND r.created_at BETWEEN _ex.created_at - INTERVAL '10 minutes' AND _ex.created_at + INTERVAL '10 minutes';

CREATE TEMP TABLE _kdx ON COMMIT DROP AS
SELECT k.id, k.product_id, k.location_id, k.color_id, k.quantity
FROM product_inventory_kardex k, _ex
WHERE k.reference_type = 'ONLINE_SALE_RETURN' AND k.reference_id = _ex.orig_id
  AND k.movement_type = 'RETURN'
  AND k.created_at BETWEEN _ex.created_at - INTERVAL '10 minutes' AND _ex.created_at + INTERVAL '10 minutes';

-- Stock: productos SIN desglose por talla se restan aquí; los que tienen sizes_data se listan abajo
UPDATE product_inventory_location pil
SET quantity = pil.quantity - t.qty, updated_at = NOW()
FROM (SELECT product_id, location_id, color_id, sum(quantity) AS qty FROM _kdx GROUP BY 1,2,3) t
WHERE pil.product_id = t.product_id AND pil.location_id = t.location_id
  AND pil.color_id IS NOT DISTINCT FROM t.color_id
  AND (pil.sizes_data IS NULL OR btrim(pil.sizes_data) IN ('', '{}', 'null'));

DELETE FROM product_inventory_kardex WHERE id IN (SELECT id FROM _kdx);
DELETE FROM return_inventory ri USING _ex
 WHERE ri.online_sale_id = _ex.orig_id AND ri.return_reason ILIKE 'CAMBIO%'
   AND ri.created_at BETWEEN _ex.created_at - INTERVAL '10 minutes' AND _ex.created_at + INTERVAL '10 minutes';
DELETE FROM online_sale_return_line WHERE return_id IN (SELECT id FROM _ret);
DELETE FROM online_sale_return WHERE id IN (SELECT id FROM _ret);

-- ---------------------------------------------------------------------
-- 1.4 Nota en la venta original
-- ---------------------------------------------------------------------
UPDATE online_sale o
SET observations = nullif(btrim(regexp_replace(
        coalesce(o.observations,''), '\s*\|?\s*CAMBIO: líneas regresadas a devoluciones[^|]*', '', 'g')), '')
FROM _ex
WHERE o.id = _ex.orig_id;

-- ---------------------------------------------------------------------
-- 1.5 Resumen antes de confirmar
-- ---------------------------------------------------------------------
-- (a) Productos con desglose por talla: su stock NO se tocó. Descontar a mano estas cantidades
--     en bodega Devoluciones con un ajuste de inventario (por talla):
SELECT k.product_id, k.location_id, k.color_id, sum(k.quantity) AS qty_a_descontar_a_mano
FROM _kdx k
JOIN product_inventory_location pil
  ON pil.product_id = k.product_id AND pil.location_id = k.location_id AND pil.color_id IS NOT DISTINCT FROM k.color_id
WHERE pil.sizes_data IS NOT NULL AND btrim(pil.sizes_data) NOT IN ('', '{}', 'null')
GROUP BY 1,2,3;

-- (b) Debe devolver 0 filas
SELECT 'queda venta' AS chk, id FROM online_sale WHERE shipment_number = 'ENVL-1604'
UNION ALL SELECT 'quedan items OP', id FROM production_order_item WHERE online_sale_id = (SELECT sale_id FROM _ex)
UNION ALL SELECT 'queda kardex', id FROM product_inventory_kardex WHERE id IN (SELECT id FROM _kdx);

-- Cambiar a COMMIT solo después de revisar (a) y (b)
ROLLBACK;
-- COMMIT;
