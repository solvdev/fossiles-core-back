-- ---------------------------------------------------------------------------
-- Finanzas por kiosco: categoria de ventas del kiosco (A, B o C), manual.
-- Se configura en Costos por kiosco > Sitios y se imprime en el Excel de Finanzas (fila "CATEGORIA VENTAS"
-- y bloque final "Kioscos A / B / C"). NULL = sin categoria. Revertir un kiosco:
--   UPDATE kiosk_site SET sales_category = NULL WHERE id = <id>;
-- Ejecutar a mano ANTES de desplegar el backend (la entidad lee la columna). Es idempotente.
-- ---------------------------------------------------------------------------
ALTER TABLE kiosk_site ADD COLUMN IF NOT EXISTS sales_category VARCHAR(1);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_kiosk_site_sales_category') THEN
        ALTER TABLE kiosk_site ADD CONSTRAINT chk_kiosk_site_sales_category
            CHECK (sales_category IS NULL OR sales_category IN ('A', 'B', 'C'));
    END IF;
END $$;

-- Verificacion
SELECT id, name, sales_category FROM kiosk_site ORDER BY sort_order, name;
