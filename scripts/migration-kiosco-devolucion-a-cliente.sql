-- Agrega DEVOLUCION_A_CLIENTE: producto entregado al cliente en un cambio (salida de stock).

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
            'TRASLADO_SALIDA',
            'TRASLADO_ENTRADA',
            'MERMA',
            'AJUSTE',
            'ANULACION',
            'CAMBIO'
        )
    );
