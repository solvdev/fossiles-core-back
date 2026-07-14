-- Reclasifica CxC de órdenes MARCAS (artículos con marca) de cartera Fossiles (OPV) a GCF (OPC).
-- La aplicación ya clasifica MARCAS como OPC al vuelo; este script alinea filas ya persistidas.

UPDATE customer_account_entry cae
SET order_kind = 'OPC'
FROM production_order po
WHERE cae.production_order_id = po.id
  AND UPPER(TRIM(COALESCE(po.order_type, ''))) = 'MARCAS'
  AND UPPER(TRIM(COALESCE(cae.order_kind, ''))) = 'OPV';
