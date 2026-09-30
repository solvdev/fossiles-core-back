-- ---------------------------------------------------------------------------
-- Finanzas por kiosco: categoria de costo fijo "Supervision" (formato de reporte 2026).
-- Monto = ((((Salarios MO indirecta + Bonificacion) * 2) * 14) / 12) / kioscos activos.
-- Es opcional: los meses anteriores a 2026 no la traen y no cuentan para "mes completo".
-- Ejecutar a mano (primero en fosstest, con pg_dump antes de produccion). Es idempotente.
-- ---------------------------------------------------------------------------
INSERT INTO kiosk_cost_category (code, name, sort_order) VALUES
    ('SUPERVISION', 'Supervisión', 11)
ON CONFLICT (code) DO NOTHING;
