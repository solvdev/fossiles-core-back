-- Deshace migration-reconciliation-base.sql. Seguro: se niega a borrar si ya hay movimientos en el libro
-- de descargas o eventos de auditoría (son de solo agregar; borrarlos destruiría el historial).
-- Para forzarlo a propósito, borre primero esas tablas a mano sabiendo lo que hace.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM recon_ledger_entry) OR EXISTS (SELECT 1 FROM recon_event) THEN
        RAISE EXCEPTION 'Hay movimientos en recon_ledger_entry o recon_event. Rollback detenido para no perder el historial.';
    END IF;
END $$;

DROP TABLE IF EXISTS recon_export_line;
DROP TABLE IF EXISTS recon_export;
DROP TABLE IF EXISTS recon_ledger_entry;
DROP TABLE IF EXISTS recon_event;
DROP TABLE IF EXISTS recon_commission_line;
DROP TABLE IF EXISTS recon_difference;
DROP TABLE IF EXISTS recon_check_result;
DROP TABLE IF EXISTS recon_receipt_application;
DROP TABLE IF EXISTS recon_receipt;
DROP TABLE IF EXISTS recon_case;
DROP TABLE IF EXISTS recon_batch;
DROP TABLE IF EXISTS recon_import_file;
DROP TABLE IF EXISTS recon_seller;
DROP FUNCTION IF EXISTS recon_forbid_change();
