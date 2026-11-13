-- Interruptores de seguro social por colaborador: no todos gozan IGSS ni llevan IRTRA/INTECAP.
-- Todos quedan marcados (como se calculaba hasta ahora); luego se desmarca a quien no corresponda.
-- Ejecutar ANTES de desplegar el back (ddl-auto=validate).

BEGIN;

ALTER TABLE employee ADD COLUMN IF NOT EXISTS igss_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE employee ADD COLUMN IF NOT EXISTS irtra_enabled BOOLEAN NOT NULL DEFAULT TRUE;

COMMIT;
