# Verificación del inventario de producto terminado

Cómo comprobar que el inventario funciona y cuadra en cada proceso: qué hacer en el sistema, qué debe pasar y con qué consulta se confirma el cuadre.

Las consultas están en [`scripts/audit-cuadre-inventario-pt.sql`](../scripts/audit-cuadre-inventario-pt.sql). **Cada consulta muestra solo lo que NO cuadra: cero filas = cuadrado.**

## Antes de empezar

1. Correr `scripts/migration-inventory-transfer-size-label.sql` en la base de datos **antes** de desplegar el back (sin la columna la aplicación no arranca).
2. Desplegar back y front.
3. Hacer el **corte de inventario** (abajo): las bodegas quedan igual al Excel de respaldo.
4. La fecha del corte es el valor de `:desde` en las consultas de cuadre.

## Corte de inventario (bodegas = Excel de respaldo)

Script: [`scripts/corte-inventario-bodega.sql`](../scripts/corte-inventario-bodega.sql). Plantilla del Excel: [`scripts/corte-inventario-plantilla.csv`](../scripts/corte-inventario-plantilla.csv).

Qué hace: respalda las filas actuales, lleva Bodega PT, Devoluciones y Devoluciones no aptas a 0 y carga lo del Excel. En el kardex queda un movimiento `CORTE_INVENTARIO` por fila (anula la historia anterior, para que ningún recálculo del kardex reviva saldos viejos) y uno `CARGA_INICIAL` por talla. No toca kioskos ni En tránsito. Todo va en una transacción y trae instrucciones para deshacer.

1. **Preparar el Excel** con columnas `ubicacion, codigo, color, talla, cantidad` (una fila por talla en cinchos; color como está en el sistema) y guardarlo como CSV.
2. **Congelar bodega** desde el corte hasta terminar: sin envíos, recepciones de producción ni despachos de ventas en línea.
3. **PASO 0:** poner fecha y referencia del corte, y los códigos reales de las ubicaciones.
4. **PASO 1:** revisar envíos en camino y ventas preparadas sin despachar. Lo que el sistema ya descontó no debe ir en el Excel.
5. **PASO 2:** crear la tabla de carga e importar el CSV.
6. **PASO 3:** todas las validaciones deben dar 0 filas (códigos, colores, cinchos sin talla, cantidades). Corregir el Excel y volver a importar hasta que estén limpias. Revisar que los totales por ubicación (3h) coincidan con el Excel.
7. **PASO 4:** aplicar. Antes de `COMMIT`, correr el PASO 5.
8. **PASO 5:** las tres verificaciones deben dar 0 filas. Si algo no cuadra, `ROLLBACK`.
9. Correr el script de cuadre con `:desde` = fecha del corte: debe salir todo en 0 filas. Esa es la línea base.

Probado en un PostgreSQL local con datos de prueba (stock descuadrado, cinchos por talla, filas sin kardex y ubicaciones que no se tocan); no se ha corrido contra la base real.

Para las pruebas conviene usar un producto normal y un cincho (con tallas), con stock conocido en Bodega PT. Anotar el stock antes de cada prueba.

## Ubicaciones

| Ubicación | Qué contiene | ¿Se despacha desde ahí? |
|---|---|---|
| Devoluciones (`BODEGA_DEV` / `DEVOLUCIONES`) | Producto devuelto revendible | Sí, primero |
| Bodega PT (`BODEGA_PT`) | Producción recibida | Sí, después de Devoluciones |
| En tránsito (`EN_TRANSITO`) | Enviado a kiosko y aún no recibido | No |
| Devoluciones no aptas (`DEV_NO_APTA`) | Devuelto dañado o usado | No |
| Kioskos | Stock del kiosko (`kiosco_stock`) | Venta POS |

`EN_TRANSITO` y `DEV_NO_APTA` se crean solas la primera vez que se usan.

## Reglas que ahora bloquea el sistema

- No se puede enviar si falta stock en Devoluciones + Bodega PT (muestra qué falta y cuánto hay).
- Ningún cincho se envía, se vende en línea desde bodega ni se traslada sin talla.
- Un ajuste se rechaza si el stock del sistema cambió mientras se contaba.
- Solo un administrador puede regresar a Bodega PT un pendiente en tránsito.

---

## 1. Producción → Bodega PT

**Pasos:** en la OP, recibir piezas en bodega PT (recepción pieza a pieza). Rechazar una pieza con motivo.

**Debe pasar:**
- Bodega PT sube 1 por cada pieza recibida (en la talla de la pieza si es cincho).
- La pieza rechazada no suma y genera tarea de reproceso.

**Cuadre:** consultas **12** y **12b** (cada pieza recibida tiene su entrada y el ítem cuenta las piezas), **1** y **3**.

## 2. Envío a kiosko

**Pasos:**
1. Crear envío con un producto normal y un cincho con talla. Confirmar y enviar.
2. Intentar enviar más de lo que hay en bodega.
3. Intentar crear o enviar un cincho sin talla.

**Debe pasar:**
- Paso 1: Devoluciones/Bodega PT bajan lo enviado (primero Devoluciones) y **En tránsito sube lo mismo**. El kiosko todavía no cambia.
- Paso 2: el sistema no deja enviar y dice qué falta.
- Paso 3: el sistema no deja guardar ni enviar.

**Cuadre:** **5** (salió de bodega lo enviado), **6** (en tránsito = enviado), **4a** (sin cinchos sin talla).

## 3. Recepción en kiosko

**Pasos:**
1. Recibir un envío completo ("Recibir todo conforme").
2. Recibir otro envío con faltante (p. ej. enviados 5, recibidos 3).
3. En el mismo envío aparece "Pendiente en tránsito: 2". Recibir 1 con **Recibir**.
4. Desde *Confirmación de recepción* (administrador), regresar el otro 1 con **Regresar a PT**.

**Debe pasar:**
- Paso 1: el kiosko sube lo enviado; En tránsito vuelve a 0 para ese envío.
- Paso 2: el kiosko sube 3; En tránsito queda en 2.
- Paso 3: el kiosko sube 1; En tránsito queda en 1.
- Paso 4: Bodega PT sube 1; En tránsito queda en 0; la línea queda con enviado 4 y recibido 4.
- No se puede recibir más de lo pendiente.

**Cuadre:** **6** y **6b** (tránsito = pendientes), **7** (entradas al kiosko = recibido), **8** (stock del kiosko = sus movimientos).

## 4. Editar, revertir o anular un envío en tránsito

**Pasos:** sobre envíos ya enviados (no recibidos): editar cantidades; "regresar a bodega" (revertir); anular.

**Debe pasar:**
- Editar: bodega y En tránsito quedan según la nueva cantidad.
- Revertir / anular: bodega recupera todo y En tránsito del envío queda en 0.

**Cuadre:** **5**, **6**, **6b**, **1**.

## 5. Venta en línea

**Pasos:**
1. Venta directa con stock: preparar desde inventario y despachar.
2. Venta con orden de producción: recibir piezas en PT y despachar al cliente.
3. Venta de un cincho sin talla: debe ser rechazada al preparar.

**Debe pasar:**
- Paso 1: bodega baja al preparar; al despachar ya no vuelve a bajar.
- Paso 2: bodega sube al recibir las piezas y baja al despachar.

**Cuadre:** **10** (toda venta despachada tuvo salida de bodega), **1**.

## 6. Devolución y cambio de venta en línea

**Pasos:**
1. Devolver una venta con dos tallas del mismo cincho, condición **BUENO**.
2. Devolver otra venta con condición **DAÑADO** o **USADO**.
3. Hacer dos cambios seguidos de la misma línea de una venta.

**Debe pasar:**
- Paso 1: Devoluciones sube las dos tallas.
- Paso 2: sube **Devoluciones no aptas**, no Devoluciones.
- Paso 3: cada cambio regresa sus unidades a Devoluciones.

**Cuadre:** **11** (lo devuelto entró a bodega por línea), **1**, **13**.

## 7. Devolución del kiosko a bodega

**Pasos:**
1. En el kiosko: devolución a bodega con condición **Apto**.
2. Otra con condición **Dañado**.
3. Boleta de devolución simple de cliente marcada apta → **Reintegrar** a bodega.

**Debe pasar:**
- El kiosko baja en los tres casos.
- Apto y reintegro: sube **Devoluciones**. Dañado: sube **Devoluciones no aptas**.

**Cuadre:** **9** (toda salida del kiosko a bodega entró a bodega), **8**.

## 8. Traslados

**Pasos:**
1. Traslado entre bodegas de un cincho **con talla**.
2. El mismo sin talla (individual y masivo).
3. Traslado entre kioskos de un cincho sin talla.

**Debe pasar:**
- Paso 1: el origen baja y el destino sube en esa talla.
- Pasos 2 y 3: el sistema no deja guardar.

**Cuadre:** **4b**, **1**, **3**, **8**.

## 9. Ajustes por conteo físico

**Pasos:**
1. Contar un producto en Bodega PT y registrar el ajuste.
2. Abrir el ajuste, hacer un envío de ese producto y luego guardar el ajuste con el stock viejo.

**Debe pasar:**
- Paso 1: el stock queda igual al conteo y el kardex registra la diferencia.
- Paso 2: el sistema rechaza el ajuste y pide recargar y volver a contar.

**Cuadre:** **1**, **3**.

## 10. Envíos simultáneos (opcional)

Dos personas envían a la vez el mismo producto desde Bodega PT. Ambos envíos deben descontar y el stock final debe ser stock inicial − (envío 1 + envío 2).

**Cuadre:** **1**, **5**.

---

## Rutina de cuadre

**Diaria (o al cierre de cada jornada de envíos):** correr las consultas 4 a 12 con `:desde` = fecha del despliegue. Todas deben dar 0 filas.

**Semanal / mensual:**
1. Correr 1, 2, 3 y 8: no deben aparecer filas nuevas respecto a la línea base.
2. Hacer conteo físico de Bodega PT, Devoluciones y Devoluciones no aptas, y compararlo con la consulta **13**.
3. Revisar **En tránsito** (consulta 6): todo lo que tenga más de unos días sin recibirse se investiga y se resuelve con **Recibir** o **Regresar a PT**.

Si una consulta devuelve filas, la fila trae el documento (envío, venta, OP o boleta) para revisarlo en el sistema.
