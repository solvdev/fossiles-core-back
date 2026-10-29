-- Rellena product_shipment_detail de los envíos históricos que se crearon solo para que el
-- cargo de cuentas por cobrar tuviera un envío. Esas filas (notes que empiezan por
-- 'ENVIO HISTORICO') no traen productos: en pantalla se ve el empaque y nada más.
--
-- Eduardo lo corre a mano. Primero en una base local. En producción, solo después de un
-- pg_dump. Este archivo no se ha ejecutado en ninguna base.
--
--   psql -v ON_ERROR_STOP=1 -f scripts/backfill-envios-historicos-detalle.sql
--
-- Así solo muestra la vista previa y termina en ROLLBACK. Para guardar:
--
--   psql -v ON_ERROR_STOP=1 -v aplicar=si -f scripts/backfill-envios-historicos-detalle.sql
--
-- Resultado esperado de OPV-66 (ENVP-00152), cargo 267: 44 líneas, 55 unidades, Q9,974,
-- igual al cargo.
--
-- QUÉ ESCRIBE
-- Una sola transacción. La única escritura es INSERT en product_shipment_detail.
-- No toca inventario, kardex, customer_account_entry, el estado del envío ni ninguna otra tabla.
-- No crea triggers ni cambia código. Las funciones quedan en pg_temp y se van con la sesión.
--
-- ALCANCE
-- product_shipment.notes LIKE 'ENVIO HISTORICO%'
-- AND status IN ('CONFIRMED', 'SENT')
-- AND sin ninguna fila en product_shipment_detail.
-- Los ítems salen de production_order_item por el production_order_id de ese envío.
--
-- COLUMNAS (la entidad no coincide del todo con la lista corta del INSERT)
-- shipment_id es la FK, como en ProductShipmentDetailEntity.
-- size_label es varchar(50) NOT NULL (default ''). Estas órdenes no traen sizes_data,
-- así que cada línea se inserta con size_label = ''. Sin esa columna el INSERT fallaría.
-- hardware_condition acepta NULL y no se escribe.
-- quantity del ítem es integer; en el detalle es numeric(12,3) NOT NULL. Se castea.
-- unit_price del detalle acepta NULL. No se deja NULL ni se inventa un monto: si no hay
-- precio defendible, la transacción aborta.
-- created_at es la marca fija 2026-10-29 00:00:00. El rollback borra solo esas filas.
-- updated_at acepta NULL y no se escribe (el @PrePersist de la aplicación no corre aquí).
--
-- PRECIO NULO
-- Si production_order_item.unit_price > 0, se usa ese.
-- Si es NULL o no es positivo, se usa la misma cascada con la que el cargo valora el ítem
-- (ProductionOrderItemPricing y CustomerAccountService.resolveProductUnitPrice):
--   orden de Luis Felipe (vendedor con LUIS FELIPE, tipo distinto de INTERNA y CLIENTE_KIOSKO):
--     seller_price si es > 0; si no sale_price si es > 0; si no discounted_price si es > 0.
--   cualquier otra orden: sale_price si es > 0; si no discounted_price si es > 0.
-- Si ninguno es positivo, se aborta. Un unit_price 0 no cuenta: el cargo lo trata igual
-- (el formulario manda 0 y el catálogo sigue vigente).
-- La parte 1 lista cada ítem con unit_price NULL, la fuente que se usaría y el monto.
--
-- TOTAL QUE SE COMPARA CON EL CARGO
-- Es el de estimateShipmentDocumentTotal, no un promedio inventado:
--   productos = suma de (cantidad x precio resuelto), redondeada por línea como itemSubtotal
--   empaque   = __OPV_PACKING__ de las observaciones, cuando no hay parcial o es la secuencia 1
--   envío     = product_shipment.shipping_cost si es > 0 (actualShippingCost). El
--               __OPV_SHIPPING__ de las observaciones no entra: el cargo no lo usa.
-- cargo_documento = CHARGE activo de la orden (product_shipment_id nulo o de este envío)
--                   + CHARGE_ADJUSTMENT activo de ese mismo envío.
-- diferencia = total_calculado - cargo_documento. DESCUADRE se ve en la vista previa de
-- todos los envíos. Un descuadre no cambia el precio que se inserta. La transacción sí
-- aborta si, después del INSERT, el total de alguna línea no reproduce esa vista previa,
-- si el conteo no es el esperado, o si falta un precio.
--
-- CONTEO
-- Aborta si los envíos sin detalle no son 63 o sus ítems no son 824.
-- La segunda corrida encuentra 0 y 0 (ya tienen detalle) y no inserta nada.
-- Para una base de prueba: -v expected_shipments=N -v expected_items=N
--
-- MARCA_CREATED_AT = 2026-10-29 00:00:00
-- Tiene que ser el mismo instante en scripts/rollback-backfill-envios-historicos-detalle.sql.
\set ON_ERROR_STOP on

\if :{?expected_shipments}
\else
  \set expected_shipments 63
\endif

\if :{?expected_items}
\else
  \set expected_items 824
\endif

\if :{?aplicar}
\else
  \set aplicar no
\endif

SELECT current_database() AS base_destino;

SELECT CASE WHEN lower(btrim(:'aplicar')) = 'si' THEN 'yes' ELSE 'no' END AS backfill_commit \gset

BEGIN;
SET LOCAL lock_timeout = '5s';

SELECT set_config('backfill.expected_shipments', :'expected_shipments', true) AS expected_shipments \gset
SELECT set_config('backfill.expected_items', :'expected_items', true) AS expected_items \gset

DO $$
DECLARE
    envios text := current_setting('backfill.expected_shipments');
    items text := current_setting('backfill.expected_items');
BEGIN
    IF envios !~ '^[0-9]+$' OR items !~ '^[0-9]+$' THEN
        RAISE EXCEPTION
            'expected_shipments y expected_items tienen que ser enteros mayores o iguales a cero. No se insertó nada.';
    END IF;
END $$;

CREATE OR REPLACE FUNCTION pg_temp.a_numero(p_text text) RETURNS numeric
LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    IF p_text IS NULL OR btrim(p_text) = '' THEN
        RETURN 0;
    END IF;
    RETURN btrim(p_text)::numeric;
EXCEPTION WHEN OTHERS THEN
    RETURN 0;
END;
$$;

-- Empaque de __OPV_PACKING__, con la misma regla includeFirstReleaseExtras:
-- entra si no hay parcial, si la secuencia es nula o si es la secuencia 1.
CREATE OR REPLACE FUNCTION pg_temp.empaque_total(
    p_observations text,
    p_partial_release_id bigint,
    p_sequence integer
) RETURNS numeric
LANGUAGE plpgsql STABLE AS $$
DECLARE
    raw text;
    total numeric;
BEGIN
    IF p_partial_release_id IS NOT NULL AND p_sequence IS NOT NULL AND p_sequence <> 1 THEN
        RETURN 0;
    END IF;

    SELECT btrim(substr(src, length('__OPV_PACKING__:') + 1))
      INTO raw
      FROM (
          SELECT replace(l.line, E'\r', '') AS src, l.n
          FROM regexp_split_to_table(coalesce(p_observations, ''), E'\n') WITH ORDINALITY AS l(line, n)
      ) s
     WHERE left(src, length('__OPV_PACKING__:')) = '__OPV_PACKING__:'
     ORDER BY n DESC
     LIMIT 1;

    IF raw IS NULL OR raw = '' THEN
        RETURN 0;
    END IF;

    BEGIN
        IF jsonb_typeof(raw::jsonb) <> 'array' THEN
            RETURN 0;
        END IF;
        SELECT coalesce(round(sum(
                   CASE
                       WHEN pg_temp.a_numero(e ->> 'quantity') > 0
                       THEN pg_temp.a_numero(e ->> 'quantity') * pg_temp.a_numero(e ->> 'unitPrice')
                       ELSE 0
                   END
               ), 2), 0)
          INTO total
          FROM jsonb_array_elements(raw::jsonb) AS e;
        RETURN total;
    EXCEPTION WHEN OTHERS THEN
        RETURN 0;
    END;
END;
$$;

CREATE OR REPLACE FUNCTION pg_temp.es_orden_lf(p_seller text, p_tipo text) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
    SELECT upper(coalesce(p_seller, '')) LIKE '%LUIS FELIPE%'
       AND upper(btrim(coalesce(p_tipo, ''))) NOT IN ('INTERNA', 'CLIENTE_KIOSKO');
$$;

-- Una sola evaluación del precio y de la fuente. monto NULL y fuente SIN_PRECIO abortan más abajo.
CREATE OR REPLACE FUNCTION pg_temp.resolver(
    p_unit numeric,
    p_lf boolean,
    p_seller numeric,
    p_sale numeric,
    p_discounted numeric,
    OUT monto numeric,
    OUT fuente text
) LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    IF p_unit > 0 THEN
        monto := round(p_unit, 2);
        fuente := 'PRECIO_ITEM';
        RETURN;
    END IF;
    IF p_lf AND p_seller > 0 THEN
        monto := round(p_seller, 2);
        fuente := 'PRECIO_VENDEDOR';
        RETURN;
    END IF;
    IF p_sale > 0 THEN
        monto := round(p_sale, 2);
        fuente := 'PRECIO_VENTA';
        RETURN;
    END IF;
    IF p_discounted > 0 THEN
        monto := round(p_discounted, 2);
        fuente := 'PRECIO_DESCUENTO';
        RETURN;
    END IF;
    monto := NULL;
    fuente := 'SIN_PRECIO';
END;
$$;

CREATE TEMP TABLE envios ON COMMIT DROP AS
SELECT
    s.id AS shipment_id,
    s.production_order_id,
    s.shipment_number,
    s.status,
    s.partial_release_id,
    po.code AS order_code,
    po.seller_name,
    po.order_type,
    CASE WHEN s.shipping_cost > 0 THEN round(s.shipping_cost, 2) ELSE 0::numeric END AS costo_envio,
    pg_temp.empaque_total(po.observations, s.partial_release_id, r.sequence_num) AS empaque,
    ch.cargos,
    ch.cargo_id,
    ch.cargo_monto,
    aj.ajuste_monto
FROM product_shipment s
JOIN production_order po ON po.id = s.production_order_id
LEFT JOIN production_order_partial_release r ON r.id = s.partial_release_id
CROSS JOIN LATERAL (
    SELECT count(*)::integer AS cargos,
           CASE WHEN count(*) = 1 THEN min(c.id) END AS cargo_id,
           round(coalesce(sum(c.amount), 0), 2) AS cargo_monto
    FROM customer_account_entry c
    WHERE c.production_order_id = s.production_order_id
      AND upper(c.entry_type) = 'CHARGE'
      AND upper(coalesce(c.status, '')) = 'ACTIVE'
      AND (c.product_shipment_id IS NULL OR c.product_shipment_id = s.id)
) ch
CROSS JOIN LATERAL (
    SELECT round(coalesce(sum(a.amount), 0), 2) AS ajuste_monto
    FROM customer_account_entry a
    WHERE a.production_order_id = s.production_order_id
      AND upper(a.entry_type) = 'CHARGE_ADJUSTMENT'
      AND upper(coalesce(a.status, '')) = 'ACTIVE'
      AND (a.product_shipment_id IS NULL OR a.product_shipment_id = s.id)
) aj
WHERE s.notes LIKE 'ENVIO HISTORICO%'
  AND s.status IN ('CONFIRMED', 'SENT')
  AND NOT EXISTS (
      SELECT 1 FROM product_shipment_detail d WHERE d.shipment_id = s.id
  );

CREATE TEMP TABLE lineas ON COMMIT DROP AS
SELECT
    e.shipment_id,
    e.shipment_number,
    e.order_code,
    i.id AS item_id,
    i.product_id,
    i.color_id,
    i.quantity,
    i.unit_price AS precio_item,
    i.sizes_data,
    r.monto,
    r.fuente
FROM envios e
JOIN production_order_item i ON i.production_order_id = e.production_order_id
LEFT JOIN product pr ON pr.id = i.product_id
CROSS JOIN LATERAL pg_temp.resolver(
    i.unit_price,
    pg_temp.es_orden_lf(e.seller_name, e.order_type),
    pr.seller_price,
    pr.sale_price,
    pr.discounted_price
) r;

CREATE TEMP TABLE vista ON COMMIT DROP AS
SELECT
    q.shipment_id,
    q.numero_envio,
    q.orden,
    q.estado_envio,
    q.lineas,
    q.unidades,
    q.precios_nulos,
    q.sin_precio,
    q.subtotal_productos,
    q.empaque,
    q.costo_envio,
    q.total_calculado,
    q.cargos,
    q.cargo_id,
    q.cargo_monto,
    q.ajuste_monto,
    q.cargo_documento,
    CASE
        WHEN q.total_calculado IS NULL THEN NULL
        ELSE round(q.total_calculado - q.cargo_documento, 2)
    END AS diferencia,
    CASE
        WHEN q.sin_precio > 0 OR q.total_calculado IS NULL THEN 'SIN_PRECIO'
        WHEN q.lineas = 0 THEN 'SIN_ITEMS'
        WHEN q.cargos = 0 THEN 'SIN_CARGO'
        WHEN q.cargos > 1 THEN 'VARIOS_CARGOS'
        WHEN round(q.total_calculado - q.cargo_documento, 2) <> 0 THEN 'DESCUADRE'
        ELSE 'OK'
    END AS estado
FROM (
    SELECT
        e.shipment_id,
        e.shipment_number AS numero_envio,
        e.order_code AS orden,
        e.status AS estado_envio,
        count(l.item_id)::integer AS lineas,
        coalesce(sum(l.quantity), 0)::numeric AS unidades,
        count(l.item_id) FILTER (WHERE l.precio_item IS NULL)::integer AS precios_nulos,
        count(l.item_id) FILTER (WHERE l.fuente = 'SIN_PRECIO')::integer AS sin_precio,
        CASE
            WHEN count(l.item_id) FILTER (WHERE l.fuente = 'SIN_PRECIO') > 0 THEN NULL
            ELSE round(coalesce(sum(round(l.monto * l.quantity, 2)), 0), 2)
        END AS subtotal_productos,
        e.empaque,
        e.costo_envio,
        CASE
            WHEN count(l.item_id) FILTER (WHERE l.fuente = 'SIN_PRECIO') > 0 THEN NULL
            ELSE round(
                round(coalesce(sum(round(l.monto * l.quantity, 2)), 0), 2)
                + e.empaque + e.costo_envio, 2)
        END AS total_calculado,
        e.cargos,
        e.cargo_id,
        e.cargo_monto,
        e.ajuste_monto,
        round(e.cargo_monto + e.ajuste_monto, 2) AS cargo_documento
    FROM envios e
    LEFT JOIN lineas l ON l.shipment_id = e.shipment_id
    GROUP BY e.shipment_id, e.shipment_number, e.order_code, e.status,
             e.empaque, e.costo_envio, e.cargos, e.cargo_id, e.cargo_monto, e.ajuste_monto
) q;

-- Parte 1. Solo SELECT. Eduardo la revisa antes de guardar.
SELECT 'REGLA_PRECIO' AS seccion,
       'unit_price del detalle acepta NULL. No se deja NULL y no se inventa un precio. '
       || 'Si el ítem trae unit_price > 0, se usa ese. Si es NULL o no es positivo, se usa la cascada del cargo: '
       || 'en Luis Felipe, seller_price, luego sale_price, luego discounted_price, cada uno solo si es > 0. '
       || 'Si ninguno aplica, la transacción aborta.' AS regla;

SELECT 'RESUMEN' AS seccion,
       count(*) AS envios,
       coalesce(sum(lineas), 0) AS items,
       coalesce(sum(unidades), 0) AS unidades,
       count(*) FILTER (WHERE estado = 'OK') AS ok,
       count(*) FILTER (WHERE estado = 'DESCUADRE') AS descuadres,
       count(*) FILTER (WHERE estado NOT IN ('OK', 'DESCUADRE')) AS otros,
       coalesce(sum(precios_nulos), 0) AS precios_nulos
FROM vista;

SELECT 'VISTA_ENVIOS' AS seccion,
       numero_envio,
       orden,
       estado_envio,
       lineas,
       unidades,
       subtotal_productos,
       empaque,
       costo_envio,
       total_calculado,
       cargo_id,
       cargo_monto,
       ajuste_monto,
       cargo_documento,
       diferencia,
       estado
FROM vista
ORDER BY numero_envio;

SELECT 'PRECIOS_NULOS' AS seccion,
       count(*) AS items
FROM lineas
WHERE precio_item IS NULL;

SELECT 'PRECIO_NULO' AS seccion,
       l.shipment_number AS numero_envio,
       l.order_code AS orden,
       l.item_id,
       l.product_id,
       l.color_id,
       l.quantity,
       l.precio_item,
       l.fuente,
       l.monto AS precio_resuelto,
       CASE
           WHEN l.fuente = 'SIN_PRECIO' THEN
               'Sin precio de ítem ni de catálogo. La transacción aborta. No se inserta un precio inventado ni se deja NULL.'
           ELSE
               'unit_price NULL. Se usará ' || l.fuente || ' (' || l.monto
               || '), la misma cascada del cargo.'
       END AS manejo
FROM lineas l
WHERE l.precio_item IS NULL
ORDER BY l.order_code, l.item_id;

SELECT 'PRECIOS_NO_POSITIVOS' AS seccion,
       l.shipment_number AS numero_envio,
       l.order_code AS orden,
       l.item_id,
       l.product_id,
       l.precio_item,
       l.fuente,
       l.monto AS precio_resuelto,
       CASE
           WHEN l.fuente = 'SIN_PRECIO' THEN
               'unit_price no positivo y sin catálogo. La transacción aborta.'
           ELSE
               'unit_price no positivo. El cargo no lo usa. Se usará ' || l.fuente || ' (' || l.monto || ').'
       END AS manejo
FROM lineas l
WHERE l.precio_item IS NOT NULL
  AND l.precio_item <= 0
ORDER BY l.order_code, l.item_id;

-- Guarda de conteo, antes de cualquier INSERT. 0 y 0 es la segunda corrida y no inserta nada.
DO $$
DECLARE
    v_envios integer := (SELECT count(*) FROM envios);
    v_items integer := (SELECT count(*) FROM lineas);
    v_esperados_envios integer := current_setting('backfill.expected_shipments')::integer;
    v_esperados_items integer := current_setting('backfill.expected_items')::integer;
    detalle text;
BEGIN
    IF v_envios = 0 AND v_items = 0 THEN
        RAISE NOTICE
            'SIN_CAMBIOS: 0 envíos y 0 ítems. Segunda corrida, o no hay envíos históricos sin detalle. No se inserta nada.';
        RETURN;
    END IF;

    IF v_envios IS DISTINCT FROM v_esperados_envios OR v_items IS DISTINCT FROM v_esperados_items THEN
        RAISE EXCEPTION
            'Conteo inesperado: % envíos y % ítems. Se esperaban % envíos y % ítems, o 0 y 0 en una segunda corrida. No se insertó nada.',
            v_envios, v_items, v_esperados_envios, v_esperados_items;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM product_shipment_detail d
        JOIN product_shipment s ON s.id = d.shipment_id
        WHERE s.notes LIKE 'ENVIO HISTORICO%'
          AND s.status IN ('CONFIRMED', 'SENT')
          AND d.created_at = TIMESTAMP '2026-10-29 00:00:00'
    ) THEN
        RAISE EXCEPTION
            'Ya hay líneas con la marca 2026-10-29 00:00:00 en envíos históricos. No se mezcla otro relleno con esa marca. No se insertó nada.';
    END IF;

    SELECT string_agg(format('%s ítem %s', order_code, item_id), ', ' ORDER BY item_id)
      INTO detalle
      FROM (
          SELECT order_code, item_id
          FROM lineas
          WHERE product_id IS NULL
          ORDER BY item_id
          LIMIT 20
      ) q;
    IF detalle IS NOT NULL THEN
        RAISE EXCEPTION
            'Hay ítems sin product_id. El detalle lo exige NOT NULL. No se insertó nada. %', detalle;
    END IF;

    SELECT string_agg(format('%s ítem %s', order_code, item_id), ', ' ORDER BY item_id)
      INTO detalle
      FROM (
          SELECT order_code, item_id
          FROM lineas
          WHERE quantity IS NULL OR quantity <= 0
          ORDER BY item_id
          LIMIT 20
      ) q;
    IF detalle IS NOT NULL THEN
        RAISE EXCEPTION
            'Hay ítems sin cantidad positiva. No se inserta un detalle en cero. No se insertó nada. %', detalle;
    END IF;

    SELECT string_agg(format('%s ítem %s', order_code, item_id), ', ' ORDER BY item_id)
      INTO detalle
      FROM (
          SELECT order_code, item_id
          FROM lineas
          WHERE sizes_data IS NOT NULL
            AND btrim(sizes_data) <> ''
            AND lower(btrim(sizes_data)) NOT IN ('{}', 'null', '[]')
          ORDER BY item_id
          LIMIT 20
      ) q;
    IF detalle IS NOT NULL THEN
        RAISE EXCEPTION
            'Hay ítems con sizes_data. El relleno es una línea por ítem y no parte tallas. No se insertó nada. %', detalle;
    END IF;

    SELECT string_agg(format('%s producto %s color %s', shipment_number, product_id, color_id), ', ')
      INTO detalle
      FROM (
          SELECT shipment_number, product_id, color_id
          FROM lineas
          WHERE color_id IS NOT NULL
          GROUP BY shipment_number, product_id, color_id
          HAVING count(*) > 1
          LIMIT 20
      ) q;
    IF detalle IS NOT NULL THEN
        RAISE EXCEPTION
            'Hay ítems repetidos por envío, producto y color. El único uq_shipment_detail_product_color_size_hw no los deja. No se insertó nada. %',
            detalle;
    END IF;

    SELECT string_agg(shipment_number, ', ' ORDER BY shipment_number)
      INTO detalle
      FROM envios
      WHERE partial_release_id IS NOT NULL;
    IF detalle IS NOT NULL THEN
        RAISE EXCEPTION
            'Hay un envío histórico con liberación parcial. No se copian todos los ítems de la orden a un parcial. No se insertó nada. %',
            detalle;
    END IF;

    SELECT string_agg(DISTINCT order_code, ', ' ORDER BY order_code)
      INTO detalle
      FROM envios e
      WHERE EXISTS (
          SELECT 1
          FROM product_shipment o
          WHERE o.production_order_id = e.production_order_id
            AND o.id <> e.shipment_id
            AND upper(btrim(coalesce(o.status, ''))) <> 'CANCELLED'
      );
    IF detalle IS NOT NULL THEN
        RAISE EXCEPTION
            'Hay otra fila de product_shipment no cancelada para la misma orden. No se reparte el ítem. No se insertó nada. Órdenes: %',
            detalle;
    END IF;

    SELECT string_agg(shipment_number, ', ' ORDER BY shipment_number)
      INTO detalle
      FROM envios e
      WHERE NOT EXISTS (SELECT 1 FROM lineas l WHERE l.shipment_id = e.shipment_id);
    IF detalle IS NOT NULL THEN
        RAISE EXCEPTION
            'Hay un envío histórico sin ítems de la orden. No se inventan líneas. No se insertó nada. %', detalle;
    END IF;

    SELECT string_agg(format('%s ítem %s', order_code, item_id), ', ' ORDER BY item_id)
      INTO detalle
      FROM (
          SELECT order_code, item_id
          FROM lineas
          WHERE fuente = 'SIN_PRECIO'
          ORDER BY item_id
          LIMIT 30
      ) q;
    IF detalle IS NOT NULL THEN
        RAISE EXCEPTION
            'Hay ítems sin precio de ítem ni de catálogo. No se inserta un precio inventado ni se deja NULL. Revise PRECIO_NULO y PRECIOS_NO_POSITIVOS. No se insertó nada. %',
            detalle;
    END IF;
END $$;

-- Parte 2. El NOT EXISTS de envío y el de línea van en el INSERT: una segunda corrida no agrega filas.
CREATE TEMP TABLE insertados (
    shipment_id bigint NOT NULL,
    quantity numeric(12, 3) NOT NULL,
    unit_price numeric(12, 2) NOT NULL
) ON COMMIT DROP;

WITH nuevos AS (
    INSERT INTO product_shipment_detail (
        shipment_id, product_id, color_id, size_label, quantity, unit_price, created_at
    )
    SELECT
        l.shipment_id,
        l.product_id,
        l.color_id,
        '',
        l.quantity::numeric(12, 3),
        l.monto,
        TIMESTAMP '2026-10-29 00:00:00'
    FROM lineas l
    WHERE l.monto IS NOT NULL
      AND l.product_id IS NOT NULL
      AND l.quantity > 0
      AND NOT EXISTS (
          SELECT 1 FROM product_shipment_detail d WHERE d.shipment_id = l.shipment_id
      )
      AND NOT EXISTS (
          SELECT 1
          FROM product_shipment_detail d
          WHERE d.shipment_id = l.shipment_id
            AND d.product_id = l.product_id
            AND d.color_id IS NOT DISTINCT FROM l.color_id
            AND d.size_label = ''
            AND d.hardware_condition IS NULL
      )
    RETURNING shipment_id, quantity, unit_price
)
INSERT INTO insertados (shipment_id, quantity, unit_price)
SELECT shipment_id, quantity, unit_price FROM nuevos;

DO $$
DECLARE
    descuadre text;
    lineas_vista integer;
    lineas_tabla integer;
    lineas_insert integer;
BEGIN
    SELECT coalesce(sum(lineas), 0) INTO lineas_vista FROM vista;
    SELECT count(*) INTO lineas_insert FROM insertados;
    SELECT count(*)
      INTO lineas_tabla
      FROM product_shipment_detail d
     WHERE d.created_at = TIMESTAMP '2026-10-29 00:00:00'
       AND d.shipment_id IN (SELECT shipment_id FROM vista);

    IF lineas_insert IS DISTINCT FROM lineas_vista OR lineas_tabla IS DISTINCT FROM lineas_vista THEN
        RAISE EXCEPTION
            'El INSERT no reprodujo la vista previa: vista % líneas, insertadas %, con la marca %. No se confirmó nada.',
            lineas_vista, lineas_insert, lineas_tabla;
    END IF;

    SELECT string_agg(
               format('%s vista %s líneas / %s unidades / total %s; quedó %s / %s / %s',
                      v.numero_envio, v.lineas, v.unidades, v.total_calculado,
                      coalesce(d.lineas, 0), coalesce(d.unidades, 0), coalesce(d.total, v.empaque + v.costo_envio)),
               '; ' ORDER BY v.numero_envio)
      INTO descuadre
      FROM vista v
      LEFT JOIN (
          SELECT d.shipment_id,
                 count(*)::integer AS lineas,
                 sum(d.quantity) AS unidades,
                 round(coalesce(sum(round(d.quantity * d.unit_price, 2)), 0), 2) AS subtotal,
                 round(coalesce(sum(round(d.quantity * d.unit_price, 2)), 0), 2) AS productos
          FROM product_shipment_detail d
          WHERE d.created_at = TIMESTAMP '2026-10-29 00:00:00'
            AND d.shipment_id IN (SELECT shipment_id FROM vista)
          GROUP BY d.shipment_id
      ) d ON d.shipment_id = v.shipment_id
     WHERE v.lineas IS DISTINCT FROM coalesce(d.lineas, 0)
        OR v.unidades IS DISTINCT FROM coalesce(d.unidades, 0)
        OR v.subtotal_productos IS DISTINCT FROM coalesce(d.subtotal, 0)
        OR v.total_calculado IS DISTINCT FROM round(coalesce(d.productos, 0) + v.empaque + v.costo_envio, 2);

    IF descuadre IS NOT NULL THEN
        RAISE EXCEPTION
            'El total insertado no coincide con la vista previa. No se confirmó nada. %', descuadre;
    END IF;
END $$;

SELECT 'INSERTADOS' AS seccion,
       (SELECT count(DISTINCT shipment_id) FROM insertados) AS envios,
       (SELECT count(*) FROM insertados) AS lineas,
       (SELECT coalesce(sum(quantity), 0) FROM insertados) AS unidades,
       (SELECT coalesce(round(sum(round(quantity * unit_price, 2)), 2), 0) FROM insertados) AS subtotal_productos;

SELECT 'CIERRE' AS seccion,
       CASE WHEN :'backfill_commit' = 'yes' THEN 'COMMIT' ELSE 'ROLLBACK' END AS modo;

\if :backfill_commit
COMMIT;
\else
ROLLBACK;
\endif
