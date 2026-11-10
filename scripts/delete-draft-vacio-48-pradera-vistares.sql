-- Conteo físico CUEROGLAM PRADERA VISTARES (location_id 26)
-- El DRAFT #48 (22-sep 08:24–23:59, 0 ítems) quedó abandonado dentro del periodo del #85 (desde 22-sep 00:16).
-- El back lo tomaba como "conteo anterior" del #85: el Ini. del #85 salía del Fin. recalculado del #48
-- (Fin. del #49 menos la venta del 22-sep de FOSS-2 T30 y la bolsa plástica) y esa venta no se veía en Vtas.
-- El fix de código (hotfix/conteo-previo-empieza-despues) ya lo ignora; este script es la vía rápida sin
-- desplegar. El #85 no está CERRADO, así que se recalcula solo al abrirlo.

-- 1) Vista previa: debe salir 1 fila, DRAFT, items = 0, movimientos = 0.
SELECT c.id, c.location_id, c.status, c.period_from_at, c.period_to_at,
       (SELECT count(*) FROM kiosco_physical_count_item i WHERE i.count_id = c.id) AS items,
       (SELECT count(*) FROM kiosco_movement m WHERE m.physical_count_id = c.id) AS movimientos,
       (SELECT count(*) FROM kiosco_physical_count_presence p WHERE p.count_id = c.id) AS presencias
FROM kiosco_physical_count c
WHERE c.id = 48;

-- 2) Borrado (la presencia se borra en cascada). Solo borra si sigue siendo el DRAFT vacío de loc 26.
BEGIN;

DELETE FROM kiosco_physical_count c
WHERE c.id = 48
  AND c.location_id = 26
  AND c.status = 'DRAFT'
  AND NOT EXISTS (SELECT 1 FROM kiosco_physical_count_item i WHERE i.count_id = c.id)
  AND NOT EXISTS (SELECT 1 FROM kiosco_movement m WHERE m.physical_count_id = c.id);
-- Debe decir DELETE 1. Si dice DELETE 0, ROLLBACK y revisar.

COMMIT;
