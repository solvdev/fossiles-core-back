-- OP consolidada (Centro de Producción): agrupa varias OPV u OPC de Luis Felipe para producirlas
-- como una sola. Las órdenes originales no cambian: cada una conserva su envío, su cobro y su empaque.
-- Correr ANTES de desplegar el binario (ddl-auto=validate no arranca sin las tablas).
-- Rollback: revertir el binario; las tablas sobrantes son inofensivas.

CREATE TABLE IF NOT EXISTS production_order_group (
  id BIGSERIAL PRIMARY KEY,
  code VARCHAR(40) NOT NULL UNIQUE,
  family VARCHAR(10) NOT NULL,               -- OPV | OPC
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | DISSOLVED
  observations VARCHAR(500),
  member_codes VARCHAR(1000),                -- códigos de las OP al crearla (queda al desagrupar)
  created_by BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  dissolved_by BIGINT,
  dissolved_at TIMESTAMP
);

-- Una OP está a lo más en una consolidada activa: al desagrupar se borran sus filas.
CREATE TABLE IF NOT EXISTS production_order_group_member (
  id BIGSERIAL PRIMARY KEY,
  group_id BIGINT NOT NULL REFERENCES production_order_group(id),
  production_order_id BIGINT NOT NULL UNIQUE REFERENCES production_order(id),
  created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_po_group_member_group ON production_order_group_member(group_id);
