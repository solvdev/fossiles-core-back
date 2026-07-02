-- Backfill de product.audience_category segun la terminacion de "name"
-- Regla: termina en A -> DAMA | termina en O o consonante -> CABALLERO | resto (E/I/U/vacio) -> UNISEX
-- Requiere que migration-product-audience-kiosk-promo.sql ya haya corrido (columna audience_category existente).
-- Ojo: la regla se basa en el ultimo caracter del nombre completo, por lo que nombres que
-- no terminan en un nombre de persona (cajas, kits, etc.) tambien caeran en la regla.
-- Revisa la vista previa antes de correr el UPDATE.

-- 1) Vista previa: que cambiaria, sin tocar nada todavia
SELECT
    id,
    name,
    category_id,
    audience_category AS actual,
    CASE
        WHEN RIGHT(UPPER(TRIM(name)), 1) = 'A' THEN 'DAMA'
        WHEN RIGHT(UPPER(TRIM(name)), 1) = 'O' THEN 'CABALLERO'
        WHEN RIGHT(UPPER(TRIM(name)), 1) ~ '^[B-DF-HJ-NP-TV-Z]$' THEN 'CABALLERO'
        ELSE 'UNISEX'
    END AS propuesto
FROM product
ORDER BY name;

-- (Opcional) Si quieres limitar el backfill a categorias especificas (ej. solo billeteras),
-- agrega esto tanto en la vista previa como en el UPDATE de abajo:
-- WHERE category_id IN (2)

-- 2) Update real, dentro de una transaccion para poder revisar antes de confirmar
BEGIN;

UPDATE product
SET audience_category = CASE
    WHEN RIGHT(UPPER(TRIM(name)), 1) = 'A' THEN 'DAMA'
    WHEN RIGHT(UPPER(TRIM(name)), 1) = 'O' THEN 'CABALLERO'
    WHEN RIGHT(UPPER(TRIM(name)), 1) ~ '^[B-DF-HJ-NP-TV-Z]$' THEN 'CABALLERO'
    ELSE 'UNISEX'
END;

-- 3) Verificar el resultado antes de confirmar
SELECT audience_category, COUNT(*) FROM product GROUP BY audience_category ORDER BY 2 DESC;

-- Si se ve bien:
COMMIT;
-- Si algo salio mal:
-- ROLLBACK;
