-- Deshace scripts/migration-lf-packing-boxes.sql (solo si el back con cajas ya fue revertido).
-- Borra las cajas y sus líneas. NO borra envíos ni liberaciones ya creados por un despacho de cajas.
-- DataGrip: termina en ROLLBACK (vista previa). Cambie por COMMIT para aplicar.

BEGIN;
SET LOCAL lock_timeout = '5s';

SELECT 'CAJAS_QUE_SE_BORRAN' AS seccion, status, count(*) AS cajas
FROM lf_packing_box
GROUP BY status
ORDER BY status;

DROP TABLE IF EXISTS lf_packing_box_line;
DROP TABLE IF EXISTS lf_packing_box;

ROLLBACK;
-- COMMIT;
