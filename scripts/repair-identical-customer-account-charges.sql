-- Conserva el cargo original y sus pagos; anula únicamente copias idénticas sin aplicaciones.
\set ON_ERROR_STOP on
\if :{?aplicar}
\else
  \set aplicar no
\endif
BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE customer_account_entry IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE duplicate_charges ON COMMIT DROP AS
WITH groups AS (
 SELECT production_order_id,min(id) original_id,count(*) charges
 FROM customer_account_entry WHERE entry_type='CHARGE' AND status <> 'VOID' AND production_order_id IS NOT NULL
 GROUP BY production_order_id HAVING count(*)>1
)
SELECT duplicate.id,original.id original_id,duplicate.production_order_id,duplicate.customer_id,duplicate.amount
FROM groups g JOIN customer_account_entry original ON original.id=g.original_id
JOIN customer_account_entry duplicate ON duplicate.production_order_id=g.production_order_id AND duplicate.id<>original.id
AND duplicate.entry_type='CHARGE' AND duplicate.status <> 'VOID'
WHERE g.charges=2 AND duplicate.customer_id=original.customer_id AND duplicate.amount=original.amount
AND duplicate.order_kind IS NOT DISTINCT FROM original.order_kind
AND duplicate.product_shipment_id IS NOT NULL AND original.product_shipment_id IS NULL
AND duplicate.partial_release_id IS NULL AND original.partial_release_id IS NULL
AND duplicate.applied_to_entry_id IS NULL AND original.applied_to_entry_id IS NULL
AND NOT EXISTS (SELECT 1 FROM customer_account_entry child WHERE child.applied_to_entry_id=duplicate.id AND child.status <> 'VOID');
SELECT * FROM duplicate_charges ORDER BY id;
SELECT customer_id,sum(amount) duplicated_debt_to_reverse FROM duplicate_charges GROUP BY customer_id ORDER BY customer_id;
\if :aplicar
 CREATE TABLE IF NOT EXISTS customer_account_duplicate_repair_log (
   entry_id BIGINT PRIMARY KEY,original_id BIGINT NOT NULL,previous_row JSONB NOT NULL,repaired_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
 );
 INSERT INTO customer_account_duplicate_repair_log(entry_id,original_id,previous_row)
 SELECT e.id,d.original_id,to_jsonb(e) FROM duplicate_charges d JOIN customer_account_entry e ON e.id=d.id ON CONFLICT DO NOTHING;
 UPDATE customer_account_entry e SET status='VOID',voided_at=CURRENT_TIMESTAMP,
 void_reason='Cargo duplicado idéntico; se conserva el cargo #' || d.original_id
 FROM duplicate_charges d WHERE e.id=d.id AND e.status <> 'VOID';
 COMMIT;
\else
 ROLLBACK;
\endif
