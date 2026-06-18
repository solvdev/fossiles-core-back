-- Modo piloto POS por kiosko (Catálogos → Ubicaciones).
-- pos_test_mode=true: ventas con test_sale=true (no cuentan en dashboard ni reporte general admin).
-- Para activar producción en un kiosko: pos_test_mode=false (sin reiniciar el servidor).

ALTER TABLE locations
    ADD COLUMN IF NOT EXISTS pos_test_mode BOOLEAN NOT NULL DEFAULT false;

-- Piloto Villa Lobos
UPDATE locations
SET pos_test_mode = true
WHERE code = 'INT_VLOBOS'
   OR UPPER(name) LIKE '%INTERPLAZA%VILLALOBOS%';
