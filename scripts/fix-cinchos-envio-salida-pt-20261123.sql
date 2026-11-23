-- =====================================================================================================
-- Cinchos que salieron de Devoluciones en vez de Bodega PT (2026-11-23).
--
-- Caso: ANDARIA-ENV-00093 (distribución 619, una de las viejas «Envío directo a kiosko») se trató como envío
-- directo: los 5 cinchos que había en Devoluciones salieron de ahí y el resto de Bodega PT. Los cinchos se guardan
-- en Bodega PT y deben salir de PT (salvo los de una OPL).
--
-- Qué hace, por cada línea de cincho de los envíos listados que siga descargada de Devoluciones:
--   · Devoluciones +N  (kardex SHIPMENT_REVERSAL)  — la pieza vuelve a Devoluciones
--   · Bodega PT    −N  (kardex SHIPMENT)           — la salida queda en Bodega PT
-- El neto de la línea no cambia (sigue en tránsito igual), solo cambia de cubeta. Si después se «regresa a bodega»
-- el envío, la reversión devuelve la pieza a Bodega PT.
--
-- Idempotente: si ya se corrigió, la línea tiene neto 0 en Devoluciones y no se toca. Falla completo (sin cambios)
-- si a Bodega PT le falta stock de alguna talla. No toca envíos de OPL (venta en línea).
--
-- Uso: psql -v ON_ERROR_STOP=1 -f fix-cinchos-envio-salida-pt-20261123.sql
-- Para otros envíos, editar la lista en «envios».
-- =====================================================================================================

BEGIN;

CREATE TEMP TABLE fix_cincho_lineas ON COMMIT DROP AS
WITH envios(shipment_number) AS (
    VALUES ('ANDARIA-ENV-00093')
),
pt AS (SELECT id FROM locations WHERE code = 'BODEGA_PT'),
dev AS (SELECT id FROM locations WHERE code = 'DEVOLUCION'),
cinchos AS (
    SELECT id FROM product
    WHERE UPPER(COALESCE(code, '')) NOT LIKE 'SUM%'
      AND (COALESCE(TRIM(cincho_type), '') <> '' OR name ILIKE '%cincho%')
),
objetivo AS (
    SELECT s.id AS shipment_id, s.shipment_number
    FROM product_shipment s
    JOIN envios e ON e.shipment_number = s.shipment_number
    LEFT JOIN production_order po ON po.id = s.production_order_id
    -- Ya salió de bodega (en tránsito o recibido); la recepción no cambia de qué cubeta salió.
    WHERE UPPER(COALESCE(s.status, '')) IN ('SENT', 'DELIVERED')
      -- Los cinchos de una OPL (venta en línea) sí salen de Devoluciones.
      AND NOT (UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%' OR UPPER(TRIM(COALESCE(po.code, ''))) = 'OPL'
           OR UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA')
)
SELECT o.shipment_id,
       o.shipment_number,
       k.reference_line_id,
       k.product_id,
       k.color_id,
       COALESCE(k.size_label, '') AS size_label,
       -SUM(k.quantity) AS qty,                  -- lo que sigue descargado de Devoluciones
       (SELECT id FROM dev) AS dev_id,
       (SELECT id FROM pt) AS pt_id
FROM product_inventory_kardex k
JOIN objetivo o ON o.shipment_id = k.reference_id
WHERE k.reference_type = 'SHIPMENT'
  AND k.movement_type IN ('SHIPMENT', 'SHIPMENT_REVERSAL')
  AND k.location_id = (SELECT id FROM dev)
  AND k.product_id IN (SELECT id FROM cinchos)
GROUP BY o.shipment_id, o.shipment_number, k.reference_line_id, k.product_id, k.color_id, COALESCE(k.size_label, '')
HAVING -SUM(k.quantity) > 0;

-- Revisión: qué se va a mover y cuánto hay en Bodega PT de esa talla.
SELECT f.shipment_number, p.code, c.name AS color, f.size_label, f.qty,
       COALESCE((NULLIF(il.sizes_data, '')::jsonb ->> f.size_label)::numeric, 0) AS pt_talla
FROM fix_cincho_lineas f
JOIN product p ON p.id = f.product_id
LEFT JOIN colors c ON c.id = f.color_id
LEFT JOIN product_inventory_location il
       ON il.product_id = f.product_id AND il.location_id = f.pt_id AND il.color_id IS NOT DISTINCT FROM f.color_id
ORDER BY 1, 2, 3, 4;

DO $$
DECLARE
    r RECORD;
    v_pt product_inventory_location%ROWTYPE;
    v_dev product_inventory_location%ROWTYPE;
    v_pt_sizes jsonb;
    v_dev_sizes jsonb;
    v_have numeric;
    v_desc text;
    v_now timestamp := (now() AT TIME ZONE 'UTC');
BEGIN
    FOR r IN SELECT * FROM fix_cincho_lineas ORDER BY shipment_id, reference_line_id LOOP
        SELECT * INTO v_pt FROM product_inventory_location
         WHERE product_id = r.product_id AND location_id = r.pt_id AND color_id IS NOT DISTINCT FROM r.color_id
         FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Sin inventario en Bodega PT para producto % color % (envío %)', r.product_id, r.color_id, r.shipment_number;
        END IF;
        SELECT * INTO v_dev FROM product_inventory_location
         WHERE product_id = r.product_id AND location_id = r.dev_id AND color_id IS NOT DISTINCT FROM r.color_id
         FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Sin renglón de inventario en Devoluciones para producto % color %', r.product_id, r.color_id;
        END IF;

        v_desc := 'Corrección: el cincho sale de Bodega PT, no de Devoluciones (' || r.shipment_number || ')';

        -- Devoluciones +qty (la talla vuelve).
        v_dev_sizes := COALESCE(NULLIF(v_dev.sizes_data, '')::jsonb, '{}'::jsonb);
        IF r.size_label <> '' THEN
            v_dev_sizes := v_dev_sizes || jsonb_build_object(r.size_label,
                COALESCE((v_dev_sizes ->> r.size_label)::numeric, 0) + r.qty);
            UPDATE product_inventory_location
               SET sizes_data = v_dev_sizes::text,
                   quantity = (SELECT COALESCE(SUM(value::numeric), 0) FROM jsonb_each_text(v_dev_sizes))
             WHERE id = v_dev.id;
        ELSE
            UPDATE product_inventory_location SET quantity = COALESCE(quantity, 0) + r.qty WHERE id = v_dev.id;
        END IF;
        INSERT INTO product_inventory_kardex (created_at, description, location_id, movement_date, movement_type,
               product_id, quantity, quantity_before, quantity_after, reference_id, reference_number, reference_type,
               color_id, size_label, reference_line_id)
        SELECT v_now, v_desc, r.dev_id, v_now, 'SHIPMENT_REVERSAL', r.product_id, r.qty,
               v_dev.quantity, il.quantity, r.shipment_id, r.shipment_number, 'SHIPMENT', r.color_id,
               NULLIF(r.size_label, ''), r.reference_line_id
          FROM product_inventory_location il WHERE il.id = v_dev.id;

        -- Bodega PT −qty (sale de aquí).
        v_pt_sizes := COALESCE(NULLIF(v_pt.sizes_data, '')::jsonb, '{}'::jsonb);
        IF r.size_label <> '' THEN
            v_have := COALESCE((v_pt_sizes ->> r.size_label)::numeric, 0);
            IF v_have < r.qty THEN
                RAISE EXCEPTION 'Bodega PT no tiene talla % del producto % (hay %, se necesitan %)',
                    r.size_label, r.product_id, v_have, r.qty;
            END IF;
            v_pt_sizes := v_pt_sizes || jsonb_build_object(r.size_label, v_have - r.qty);
            UPDATE product_inventory_location
               SET sizes_data = v_pt_sizes::text,
                   quantity = (SELECT COALESCE(SUM(value::numeric), 0) FROM jsonb_each_text(v_pt_sizes))
             WHERE id = v_pt.id;
        ELSE
            IF COALESCE(v_pt.quantity, 0) < r.qty THEN
                RAISE EXCEPTION 'Bodega PT no tiene el producto % (hay %, se necesitan %)', r.product_id, v_pt.quantity, r.qty;
            END IF;
            UPDATE product_inventory_location SET quantity = quantity - r.qty WHERE id = v_pt.id;
        END IF;
        INSERT INTO product_inventory_kardex (created_at, description, location_id, movement_date, movement_type,
               product_id, quantity, quantity_before, quantity_after, reference_id, reference_number, reference_type,
               color_id, size_label, reference_line_id)
        SELECT v_now, v_desc, r.pt_id, v_now, 'SHIPMENT', r.product_id, -r.qty,
               v_pt.quantity, il.quantity, r.shipment_id, r.shipment_number, 'SHIPMENT', r.color_id,
               NULLIF(r.size_label, ''), r.reference_line_id
          FROM product_inventory_location il WHERE il.id = v_pt.id;
    END LOOP;
END $$;

-- Verificación: por envío, cuánto quedó descargado de cada cubeta en cincho (Devoluciones debe dar 0).
SELECT s.shipment_number, l.code AS bodega, -SUM(k.quantity) AS piezas_descargadas
FROM product_inventory_kardex k
JOIN product_shipment s ON s.id = k.reference_id
JOIN locations l ON l.id = k.location_id
WHERE k.reference_type = 'SHIPMENT'
  AND k.movement_type IN ('SHIPMENT', 'SHIPMENT_REVERSAL')
  AND s.shipment_number IN ('ANDARIA-ENV-00093')
  AND l.code IN ('BODEGA_PT', 'DEVOLUCION')
GROUP BY 1, 2
ORDER BY 1, 2;

COMMIT;
