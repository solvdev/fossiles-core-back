-- Agrega el tipo de movimiento SALIDA a kiosco_movement.
-- Se usa para la venta POS de Entrecueros SIN FACTURA: no es venta (Vtas.) ni devolucion, es una salida (Sal.).
--
-- EJECUTAR ANTES de desplegar el back: sin esto la base rechaza los INSERT con movement_type = 'SALIDA'
-- (chk_kiosco_movement_type). Es idempotente.

ALTER TABLE kiosco_movement
    DROP CONSTRAINT IF EXISTS chk_kiosco_movement_type;

ALTER TABLE kiosco_movement
    ADD CONSTRAINT chk_kiosco_movement_type CHECK (
        movement_type IN (
            'ENTRADA',
            'VENTA',
            'DEVOLUCION_DEPOSITO',
            'DEVOLUCION_CLIENTE',
            'DEVOLUCION_A_CLIENTE',
            'SALIDA',
            'TRASLADO_SALIDA',
            'TRASLADO_ENTRADA',
            'MERMA',
            'AJUSTE',
            'ANULACION',
            'CAMBIO'
        )
    );
