-- Cajas de armado de Bodega PT para órdenes OPV / OPC de Luis Felipe.
-- Tablas nuevas: lf_packing_box y lf_packing_box_line. No toca ninguna tabla existente.
--
-- ORDEN DE DESPLIEGUE: COMMIT este script ANTES de desplegar el back (ddl-auto=validate falla si faltan las tablas).
-- Rollback: revertir la app primero y luego correr scripts/migration-lf-packing-boxes-rollback.sql.
-- DataGrip: corre el archivo completo. Por defecto termina en ROLLBACK (vista previa).
-- Para aplicar, cambie el ROLLBACK final por COMMIT. No lo ejecuta el backend.

BEGIN;
SET LOCAL lock_timeout = '5s';

-- Guardia: las tablas a las que apuntan las llaves foráneas deben existir.
DO $$
BEGIN
    IF to_regclass('public.production_order') IS NULL
       OR to_regclass('public.production_order_item') IS NULL
       OR to_regclass('public.production_order_partial_release') IS NULL
       OR to_regclass('public.product_shipment') IS NULL THEN
        RAISE EXCEPTION 'Faltan tablas base (production_order, production_order_item, production_order_partial_release, product_shipment).';
    END IF;
END $$;

-- Una caja por orden y número de caja (1, 2, 3...). status: OPEN -> CLOSED -> DISPATCHED.
-- partial_release_id / shipment_id se llenan solo al despachar.
CREATE TABLE IF NOT EXISTS lf_packing_box (
    id                  bigserial PRIMARY KEY,
    production_order_id bigint      NOT NULL REFERENCES production_order (id),
    box_no              integer     NOT NULL,
    status              varchar(20) NOT NULL DEFAULT 'OPEN',
    partial_release_id  bigint      REFERENCES production_order_partial_release (id),
    shipment_id         bigint      REFERENCES product_shipment (id),
    notes               text,
    created_at          timestamp,
    created_by          bigint,
    updated_at          timestamp,
    updated_by          bigint,
    CONSTRAINT uq_lf_packing_box_order_no UNIQUE (production_order_id, box_no),
    CONSTRAINT ck_lf_packing_box_status CHECK (status IN ('OPEN', 'CLOSED', 'DISPATCHED')),
    CONSTRAINT ck_lf_packing_box_no CHECK (box_no > 0)
);

-- Piezas de la caja por línea de la orden y talla. size_key = '' cuando la línea no tiene tallas.
CREATE TABLE IF NOT EXISTS lf_packing_box_line (
    id                       bigserial PRIMARY KEY,
    box_id                   bigint      NOT NULL REFERENCES lf_packing_box (id) ON DELETE CASCADE,
    production_order_item_id bigint      NOT NULL REFERENCES production_order_item (id),
    size_key                 varchar(40) NOT NULL DEFAULT '',
    quantity                 integer     NOT NULL,
    CONSTRAINT uq_lf_packing_box_line UNIQUE (box_id, production_order_item_id, size_key),
    CONSTRAINT ck_lf_packing_box_line_qty CHECK (quantity > 0)
);

CREATE INDEX IF NOT EXISTS idx_lf_packing_box_line_item
    ON lf_packing_box_line (production_order_item_id);

-- Para ligar rápido las cajas de un envío / liberación (consulta de etiquetas y búsqueda por envío).
CREATE INDEX IF NOT EXISTS idx_lf_packing_box_shipment
    ON lf_packing_box (shipment_id) WHERE shipment_id IS NOT NULL;

-- Verificación: las dos tablas, sus restricciones e índices.
SELECT 'TABLAS' AS seccion, table_name
FROM information_schema.tables
WHERE table_schema = 'public' AND table_name IN ('lf_packing_box', 'lf_packing_box_line')
ORDER BY table_name;

SELECT 'RESTRICCIONES' AS seccion, conrelid::regclass AS tabla, conname, contype
FROM pg_constraint
WHERE conrelid IN ('lf_packing_box'::regclass, 'lf_packing_box_line'::regclass)
ORDER BY conrelid::regclass::text, conname;

SELECT 'INDICES' AS seccion, tablename, indexname
FROM pg_indexes
WHERE schemaname = 'public' AND tablename IN ('lf_packing_box', 'lf_packing_box_line')
ORDER BY tablename, indexname;

ROLLBACK;
-- COMMIT;
