# Bodega PT: carga y descarga de inventario

Cómo deben moverse las existencias de producto terminado, qué las mueve y cómo verificar que
documento e inventario cuentan lo mismo. Para la operación de recepción pieza a pieza ver
[BODEGA-PT.md](BODEGA-PT.md).

## La regla que no se puede romper

**Una unidad existe en exactamente una ubicación a la vez.** Si un documento la mueve, tiene que
haber una salida en el origen y una entrada en el destino. Si solo hay entrada, la unidad se
duplicó; si solo hay salida, se perdió.

De ahí se derivan las dos comprobaciones que valen para todo:

- Para cada producto, color y ubicación: `product_inventory_location.quantity` = suma con signo de
  su kardex.
- Para cada documento de traslado: unidades que entran al destino = unidades que salen del origen.

## Ubicaciones que participan

| Ubicación | `code` | Papel |
|---|---|---|
| Bodega Producto Terminado | `BODEGA_PT` | Entra lo producido, sale todo lo que se despacha |
| Bodega Devoluciones | `BODEGA_DEVOLUCIONES` y variantes | Recibe devoluciones; **se consume antes que PT** |
| Kioskos | `categoria = KIOSKO` | Destino de envíos; su recepción carga stock |

El orden Devoluciones → PT lo define `ProductInventoryService.getDispatchSourceWarehouses()` y lo
respetan todas las descargas. Nunca se descarga PT si Devoluciones tiene existencias de esa variante.

## Carga: qué suma en Bodega PT

La única entrada normal es la **recepción de piezas de una orden de producción**, pieza por pieza,
en `/admin/warehouse-view`. Cada pieza marcada `RECEIVED` suma 1 unidad y escribe un
`PRODUCTION_ENTRY` con `reference_type = PRODUCTION_ORDER_WH_UNIT` y un `reference_number` único por
pieza (`{código OP}-WH-UNIT-{id}`), que es lo que la hace idempotente.

Las otras entradas posibles son transferencias entre ubicaciones, ajustes de inventario y la
reversión de una salida (ver más abajo). Crear una OP, avanzar tareas o cerrar producción **no**
suman stock: solo la recepción física lo hace.

## Descarga: qué resta en Bodega PT

Toda salida pasa por `ProductInventoryService.decrementFromDispatchWarehouses(...)`, que consume
Devoluciones y luego PT, y registra una fila de kardex negativa por cada línea del documento.

| Flujo | Punto de disparo | `reference_type` / `movement_type` |
|---|---|---|
| Envío de distribución o a kiosko | `ProductDistributionService.sendShipment` | `SHIPMENT` |
| Envío interno ENVI | `dispatchStandaloneInternal` | `SHIPMENT` |
| Venta en línea | preparación de la venta (`consumeAcrossWarehouses`) | `ONLINE_SALE_PREPARE` |
| Envío legacy de distribución | `DistribucionService.enviarEnvio` | `DISTRIBUTION_EXIT` |

**Los envíos a kiosko descargan bodega igual que los demás.** La recepción en el kiosko carga stock
allá, así que si el envío no descargara, las mismas unidades quedarían contadas en los dos lados.

### Qué NO mueve inventario

Conviene tenerlo explícito porque es fuente recurrente de confusión:

- Confirmar un borrador de envío (`confirm-draft`): solo marca piezas PT como asignadas al envío.
- Despachar venta en línea ya preparada desde inventario (`dispatchDirectOnlineSale` / prepare previo
  con `ONLINE_SALE_PREPARE`): no vuelve a descontar; el stock ya salió en la preparación.
- Despachar OPL producida (`dispatchCustomerShipment` con unidades RECEIVED en bodega PT): **sí
  descuenta** Devoluciones/PT por unidad (`ONLINE_SALE_DISPATCH`, idempotente por `unit.id`).
- Documentos OPI (orden interna sin ubicación destino): son documento, no traslado.
- Empaques `SUM-`: su salida ocurre en entrega de materiales, no en el envío.

## Idempotencia: el candado es la línea del documento

Cada movimiento de salida se identifica por **tipo de referencia, id del documento, tipo de
movimiento, producto, ubicación, color y `reference_line_id`**, donde `reference_line_id` es el id de
la línea (`product_shipment_detail.id`, `online_sale_item.id`, `envio_detalle.id`).

La línea es imprescindible: un envío puede llevar dos tallas del mismo producto y color en dos
líneas distintas, y sin ella el sistema toma la segunda por un duplicado de la primera y no la
descarga. El kardex guarda además `size_label` para que la salida quede trazada por talla.

Reglas de implementación, si tocas este código:

- Todo llamado a `decrementFromDispatchWarehouses` debe pasar el id de la línea. El parámetro es
  opcional solo por compatibilidad con datos anteriores a la migración.
- El chequeo de "ya aplicado" es por **neto**, no por existencia: se suman las salidas (negativas) y
  las reversiones (positivas) de esa línea. Así una línea revertida vuelve a poder descargarse.
- `decrementFromDispatchWarehouses` descuenta de `remaining` solo lo que realmente se aplicó. Nunca
  asumas que se consumió lo solicitado.
- Si falla el registro del kardex, la operación se revierte entera. Un decremento sin su fila de
  kardex deja el neto en cero y un reintento volvería a descontar el mismo stock.

## Reversiones

`ProductInventoryService.reverseDispatchOutflows(...)` devuelve las unidades a la ubicación exacta de
la que salieron, respetando la talla, y **escribe su propia fila de kardex** con tipo
`{movimiento}_REVERSAL` y cantidad positiva. Es idempotente por neto: una línea ya revertida queda en
cero y se ignora en llamadas posteriores.

Se usa al regresar a bodega un envío en tránsito (`revertSentShipment`) y al editar las líneas de un
envío ya enviado, donde primero se revierte todo y luego se vuelve a descargar la nueva composición.

## Cinchos FOSS y tallas

Los productos cincho FOSS llevan el desglose por talla en `product_inventory_location.sizes_data`, y
`quantity` es siempre la suma de ese desglose. Cuando hay desglose, **la talla es obligatoria** para
cargar y para descargar: un documento sin talla se rechaza con un mensaje explícito en lugar de ver
disponibilidad cero y perder la salida en silencio.

## Puesta en marcha

1. Aplicar `scripts/migration-product-inventory-kardex-line-ref.sql` **antes** de desplegar. El
   backend corre con `ddl-auto=validate` y no arranca sin las columnas nuevas. El script también
   rellena talla y línea en las salidas históricas que se pueden identificar sin ambigüedad.
2. Cuantificar el desfase acumulado con `scripts/audit-bodega-pt-salidas-faltantes.sql`. La consulta
   1b da, por producto y color, las unidades que entraron a kioskos sin haber salido de bodega.
3. Ajustar esas diferencias con un ajuste de inventario documentado. **Las correcciones aplican de
   aquí en adelante**: los envíos ya despachados sin descarga no se corrigen solos.
4. Volver a correr el script de auditoría: las consultas 1, 2 y 3 deben quedar vacías y la 5 sin
   diferencias entre stock y kardex.

## Auditoría periódica

- `scripts/audit-bodega-pt-salidas-faltantes.sql` — salidas que faltan, colisiones por talla,
  descuadre stock contra kardex.
- `scripts/audit-bodega-pt-production-inventory.sql` — entradas por producción y doble recepción.

La consulta 5 del primero (stock actual contra neto de kardex, por ubicación) es la más útil como
chequeo de rutina: cualquier fila que aparezca ahí es un movimiento que tocó existencias sin dejar
rastro, o al revés.

## Limitaciones conocidas

- **Costo FIFO tras una reversión.** La reversión devuelve unidades pero no reconstruye los lotes
  FIFO consumidos, así que un reenvío posterior puede quedar sin costo unitario en el kardex. Afecta
  la valuación, no las existencias.
- **Envíos legacy sin color ni talla.** `envio_detalle` no guarda variante. El sistema resuelve el
  color cuando el producto tiene una sola en las bodegas de despacho; con varias variantes o con
  cinchos por talla, la descarga falla con un mensaje claro en lugar de adivinar. Ese flujo está
  deprecado: usar `ProductDistributionService`.
- **Movimientos anteriores a la migración** tienen `reference_line_id` nulo y se netean entre sí como
  un solo grupo.

## Pruebas

`ProductDispatchInventoryTest` cubre los invariantes de este documento: descarga en envíos a kiosko,
descarga independiente de dos tallas del mismo producto y color, idempotencia de la reversión, y
edición de un envío en tránsito. `ProductionOrderWarehouseUnitServiceTest` cubre la carga.
