-- Agrega el tipo CAMBIO al constraint de movimientos de kiosco
-- Un cambio es un intercambio de producto: el cliente devuelve un artículo y recibe otro.
-- Se crean dos movimientos: +quantity sobre el producto devuelto y -quantity sobre el producto entregado.

ALTER TABLE kiosco_movement
    DROP CONSTRAINT IF EXISTS chk_kiosco_movement_type;

ALTER TABLE kiosco_movement
    ADD CONSTRAINT chk_kiosco_movement_type CHECK (
        movement_type IN (
            'ENTRADA',
            'VENTA',
            'DEVOLUCION_DEPOSITO',
            'DEVOLUCION_CLIENTE',
            'TRASLADO_SALIDA',
            'TRASLADO_ENTRADA',
            'MERMA',
            'AJUSTE',
            'ANULACION',
            'CAMBIO'
        )
    );
