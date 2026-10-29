-- La reparación exige una variante única y cantidades compatibles; el resto queda para conciliación.
\set ON_ERROR_STOP on
\if :{?aplicar}
\else
  \set aplicar no
\endif
BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE task, task_item, production_order_item IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE reference_candidates ON COMMIT DROP AS
WITH missing AS (
 SELECT ti.id,ti.task_id,ti.production_order_item_id previous_id,ti.product_id,ti.color_id,ti.quantity,t.production_order_id,t.status
 FROM task_item ti JOIN task t ON t.id=ti.task_id
 LEFT JOIN production_order_item old ON old.id=ti.production_order_item_id
 WHERE ti.production_order_item_id IS NOT NULL AND old.id IS NULL
), candidates AS (
 SELECT m.*,count(poi.id) matches,min(poi.id) target_id,array_agg(poi.id ORDER BY poi.id) FILTER (WHERE poi.id IS NOT NULL) candidate_ids
 FROM missing m LEFT JOIN production_order_item poi ON poi.production_order_id=m.production_order_id
 AND poi.product_id=m.product_id AND poi.color_id IS NOT DISTINCT FROM m.color_id
 GROUP BY m.id,m.task_id,m.previous_id,m.product_id,m.color_id,m.quantity,m.production_order_id,m.status
), order_quantities AS (
 SELECT poi.id,CASE WHEN poi.quantity>0 THEN poi.quantity ELSE sizes.quantity END quantity
 FROM production_order_item poi LEFT JOIN LATERAL (
   SELECT CASE WHEN bool_and(value ~ '^[0-9]+$') THEN sum(CASE WHEN value ~ '^[0-9]+$' THEN value::numeric END) END quantity
   FROM jsonb_each_text(CASE
     WHEN pg_input_is_valid(coalesce(nullif(poi.sizes_data,''),'{}'),'jsonb')
     THEN CASE WHEN jsonb_typeof(coalesce(nullif(poi.sizes_data,''),'{}')::jsonb)='object'
       THEN coalesce(nullif(poi.sizes_data,''),'{}')::jsonb ELSE '{}'::jsonb END
     ELSE '{}'::jsonb END)
 ) sizes ON true
), assigned AS (
 SELECT ti.production_order_item_id id,sum(ti.quantity) quantity FROM task_item ti JOIN task t ON t.id=ti.task_id
 WHERE t.status <> 'CANCELLED' GROUP BY ti.production_order_item_id
), proposed AS (
 SELECT target_id,sum(quantity) FILTER (WHERE status <> 'CANCELLED') quantity FROM candidates WHERE matches=1 GROUP BY target_id
)
SELECT c.*,coalesce(a.quantity,0)+coalesce(p.quantity,0) assigned_quantity,poi.quantity ordered_quantity,
 CASE WHEN matches<>1 THEN 'VARIANTE_AMBIGUA_O_AUSENTE'
      WHEN c.quantity IS NULL OR c.quantity<=0 OR poi.quantity IS NULL OR poi.quantity<=0 THEN 'CANTIDAD_NO_VERIFICABLE'
      WHEN coalesce(a.quantity,0)+coalesce(p.quantity,0)>poi.quantity THEN 'CANTIDAD_EXCEDE_ORDEN'
      ELSE 'VERIFICADA' END result
FROM candidates c LEFT JOIN order_quantities poi ON poi.id=c.target_id
LEFT JOIN assigned a ON a.id=c.target_id LEFT JOIN proposed p ON p.target_id=c.target_id;
SELECT result,status,count(*) FROM reference_candidates GROUP BY result,status ORDER BY result,status;
SELECT * FROM reference_candidates WHERE result <> 'VERIFICADA' ORDER BY status,id;
\if :aplicar
 CREATE TABLE IF NOT EXISTS production_reference_repair_log (
   entity_type VARCHAR(20) NOT NULL,entity_id BIGINT NOT NULL,previous_id BIGINT NOT NULL,target_id BIGINT NOT NULL,
   repaired_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(entity_type,entity_id,previous_id)
 );
 INSERT INTO production_reference_repair_log(entity_type,entity_id,previous_id,target_id)
 SELECT 'task_item',id,previous_id,target_id FROM reference_candidates WHERE result='VERIFICADA' ON CONFLICT DO NOTHING;
 UPDATE task_item ti SET production_order_item_id=c.target_id FROM reference_candidates c
 WHERE c.result='VERIFICADA' AND ti.id=c.id AND ti.production_order_item_id=c.previous_id;
 -- La cabecera heredada se recupera solo si todos sus productos demuestran la misma referencia.
 CREATE TEMP TABLE header_repair ON COMMIT DROP AS
 SELECT t.id,t.production_order_item_id previous_id,min(ti.production_order_item_id) target_id
 FROM task t JOIN task_item ti ON ti.task_id=t.id
 LEFT JOIN production_order_item old ON old.id=t.production_order_item_id
 JOIN production_order_item current ON current.id=ti.production_order_item_id AND current.production_order_id=t.production_order_id
 WHERE t.production_order_item_id IS NOT NULL AND old.id IS NULL
 GROUP BY t.id,t.production_order_item_id
 HAVING count(*)=(SELECT count(*) FROM task_item all_items WHERE all_items.task_id=t.id)
 AND count(DISTINCT ti.production_order_item_id)=1;
 INSERT INTO production_reference_repair_log(entity_type,entity_id,previous_id,target_id)
 SELECT 'task',id,previous_id,target_id FROM header_repair ON CONFLICT DO NOTHING;
 UPDATE task t SET production_order_item_id=r.target_id FROM header_repair r WHERE t.id=r.id AND t.production_order_item_id=r.previous_id;
 COMMIT;
\else
 ROLLBACK;
\endif
