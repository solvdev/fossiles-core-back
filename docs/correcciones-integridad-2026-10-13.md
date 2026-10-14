# Integridad de órdenes, producción y compras

## Reglas de operación

1. Los formatos admiten HTML de impresión sanitizado en encabezado, cuerpo y pie. La limpieza se aplica al guardar y al consultar formatos existentes. Se conservan las variables `{{nombre}}`, las tablas y los estilos permitidos.
2. Cada petición con JWT verifica que el usuario siga activo y conserve el mismo identificador. Un usuario desactivado o eliminado pierde acceso aunque su token no haya vencido.
3. La edición de una orden conserva los identificadores y cantidades recibidas de sus ítems. Un ítem con tareas, recepción, liberaciones o vínculos de venta conserva su variante y cantidades; admite cambios de precio y observaciones. La validación precede a la escritura y cualquier error revierte la transacción completa.
4. La entrega de cuero por producto genera una salida del kardex según la receta y descuenta ft² una sola vez. La salida conserva el identificador del ítem al trasladarlo entre tareas. Una entrega manual que cubre la orden se reutiliza; una entrega manual parcial requiere conciliación antes de registrar entregas por producto. La reversión se permite antes de troquelado, entrega de materiales o inicio de producción. Las tareas con movimientos conservan su historial.
5. El troquelado de toda una tarea actualiza sus ítems. La operación masiva valida todas las tareas antes de escribir. Los traslados y la separación de productos recalculan las compuertas de cuero y troquelado desde el detalle que permanece en cada tarea.
6. Una recepción exige cantidades explícitas y un renglón por material. Las recepciones activas se acumulan para determinar el estado de la compra. El valor acordado se descarga de compras en tránsito hasta la cantidad ordenada; los excesos y diferencias de precio ajustan cuentas por pagar.
7. La edición o anulación de una recepción corrige sus propios lotes, inventario y asientos en una sola transacción. Los asientos originales se conservan y los cambios se registran como `MATERIAL_RECEIPT_ADJUSTMENT`. Los reintentos no duplican ajustes. El material ya consumido no puede devolverse ni cambiar de costo desde compras; los lotes históricos incompletos requieren conciliación.
8. Los filtros contables de documento, cuenta y fechas se aplican juntos. Se admiten límites de fecha independientes y se rechazan rangos invertidos.

## Migraciones preparadas

| Orden | Archivo | Finalidad |
| --- | --- | --- |
| 1 | `scripts/migration-print-format-body.sql` | Persistir el cuerpo del formato. |
| 2 | `scripts/migration-task-leather-movement.sql` | Vincular movimientos a ítems, conservar su referencia y limitar una salida activa por ítem. |
| 3 | `scripts/migration-material-receipt-status.sql` | Conservar recepciones anuladas y su motivo. |
| 4 | `scripts/migration-production-reference-integrity.sql` | Proteger nuevas referencias y evitar la eliminación en cascada de unidades recibidas. |
| 5 | `scripts/migration-customer-account-lf-phase2.sql` | Impedir cargos activos duplicados y cargos activos sin orden. |

Las cinco migraciones se probaron en una copia temporal de PostgreSQL 18 y el 14 de octubre se aplicaron a `fossiles_local`, con respaldo previo. Las columnas de formatos, cuero y recepciones deben existir antes de arrancar el backend actualizado: con `ddl-auto=validate` no inicia sin ellas. El despliegue actual detiene el servicio y arranca el jar nuevo sin migrar, así que en coretest y producción los scripts se ejecutan antes, en el orden de la tabla. La fase 2 de CxC exige resolver los cargos duplicados y comprobar que todos los servicios que escriben utilicen la versión compatible.

Las claves foráneas de tareas se crean con `NOT VALID`: protegen las nuevas referencias y conservan los casos históricos pendientes. Su validación completa corresponde después de conciliar esos casos.

## Reparación de la copia local

Se recuperaron 100 referencias de `task_item` y 21 cabeceras de `task`. La coincidencia exige la misma orden, producto y color, una sola variante y cantidades compatibles, incluyendo la suma por talla de cinchos. Los valores anteriores quedan en `production_reference_repair_log`.

Quedan 410 ítems y 19 cabeceras para conciliación. De los ítems, 299 no tienen una variante única y 111 excederían la cantidad actualmente ordenada; 165 pertenecen a tareas pendientes. Las referencias originales se conservan hasta demostrar su destino.

Los cargos 70, 74 y 75 se anularon por duplicar exactamente los cargos originales 20, 5 y 3. Se conservaron los pagos y notas de crédito aplicados a los originales. La deuda duplicada eliminada suma Q8,497.00. Los registros anteriores quedan en `customer_account_duplicate_repair_log`.

Los scripts `repair-production-task-item-references.sql` y `repair-identical-customer-account-charges.sql` realizan una simulación con rollback por defecto. La opción `aplicar=on` confirma la escritura. Un respaldo completo anterior a las correcciones y los listados de conciliación se conservan fuera del repositorio de código.

## Verificación

Las pruebas cubren HTML peligroso, revocación por estado del usuario, identidad de ítems, recepciones parciales y excesos, edición de precio, anulación, rollback por fallo contable, movimientos de cuero, troquelado y traslados. También se ejecutan regresiones de recepciones de producción y liberaciones parciales.

Resultado: 59 pruebas seleccionadas de backend y 6 de frontend aprobadas, sin omisiones. El backend generó `bootJar` y el frontend completó su compilación de producción. La revisión de espacios del diff pasó en ambos repositorios.

En la copia temporal se comprobó el rechazo de cargos duplicados, cargos sin orden y nuevas referencias huérfanas. La compilación y las pruebas seleccionadas complementan la auditoría previa; sus fallos históricos fuera de este alcance mantienen su seguimiento independiente.

## Ajustes del 14 de octubre

### Cuero

1. La sincronización por orden solo abre la compuerta de cuero; nunca la cierra. Una bandera sin salida propia puede venir de una entrega anterior al kardex por producto y se conserva.
2. Al anular una salida se retiran únicamente las banderas que dependían de ella: la del producto con esa salida, o las que una entrega por orden abrió desde su registro y que ya no quedan cubiertas.
3. Si la orden ya tiene salidas por producto, una entrega por orden del mismo cuero solo admite lo que falta para completar la receta.
4. Anular la misma salida desde el kardex y desde el producto bloquea el movimiento: la segunda anulación no vuelve a sumar stock.
5. Los ítems con movimientos de cuero, incluidos los anulados, no se borran: el auto-plan los trata como avance y la regeneración de tareas los rechaza con un mensaje claro.
6. Las tareas de cinchos nacen con cuero y corte marcados; esas marcas no impiden eliminarlas.
7. Volver a marcar troquelado o recalcular compuertas conserva la fecha original.

### Inventario y compras

1. Las salidas del kardex por corrección o anulación de recepción se guardan en negativo, igual que los consumos.
2. En materiales sin conversión de compra, el costo unitario es el promedio ponderado de las recepciones y se revierte al corregirlas o anularlas. Con conversión, la entidad lo fija como precio de compra entre conversión, como antes.
3. Un material no marcado en una recepción no crea renglón en cero.
4. Una recepción histórica sin renglones no bloquea las nuevas recepciones de su compra ni se reconcilia; editarla exige conciliar antes sus materiales.
5. La compra guarda su centro de costo, que heredan recepciones, ajustes y anulación.
6. Una compra sin asientos de origen se puede anular: no hay nada que revertir.

### Acceso

En una ruta pública, un token vencido o de un usuario inactivo deja pasar la petición como anónima; en las demás responde 401. El token por URL solo se acepta en el stream de anuncios. Las rutas públicas y los permisos por rol siguen como en `develop`.

### Errores y transacciones

1. Un error interno responde 500 con una referencia; el detalle queda en el log. Parámetros o cuerpos con formato inválido responden 400.
2. Entregar materiales a una tarea, agregar ventas del día, transferir inventario en lote y cambiar productos de un envío despachado revierten toda la operación si fallan a mitad.
