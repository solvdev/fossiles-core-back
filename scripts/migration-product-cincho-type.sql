-- Tipo de cincho en productos FOSS: CASUAL | REVERSIBLE | NULL
ALTER TABLE product ADD COLUMN IF NOT EXISTS cincho_type VARCHAR(20);

-- Backfill desde nombre del producto donde aplique
UPDATE product
SET cincho_type = 'CASUAL'
WHERE cincho_type IS NULL
  AND upper(name) LIKE '%CASUAL%'
  AND upper(name) NOT LIKE '%REVERSIBLE%';

UPDATE product
SET cincho_type = 'REVERSIBLE'
WHERE cincho_type IS NULL
  AND upper(name) LIKE '%REVERSIBLE%';
