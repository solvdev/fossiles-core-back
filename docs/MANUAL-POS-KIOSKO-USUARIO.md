# Manual del POS Kiosko Fossiles

**Para encargadas, cajeras y administradores**  
Guía sencilla — sin tecnicismos.

---

## ¿Qué es esto?

El sistema te ayuda a **vender en el kiosko**, **controlar la caja**, **ver cuánto hay en inventario** y **recibir mercadería** que viene del almacén.

Hay **dos pantallas principales** en el menú:

| Menú | Para qué sirve |
|------|----------------|
| **Ventas del Kiosko** | Vender, cobrar, abrir/cerrar caja, ver reportes del día |
| **Inventario del Kiosko** | Ajustes de stock, traslados, mermas, conteos físicos (uso administrativo) |

Dentro de **Ventas del Kiosko** también hay una pestaña **Inventario** (consulta rápida). No es lo mismo que el módulo completo de inventario; abajo te explicamos la diferencia.

---

## Antes de empezar

### ¿Quién ve qué?

- **Encargada del kiosko:** ve solo su kiosko. Puede vender, manejar caja e inventario de consulta.
- **Administrador:** puede cambiar de kiosko con el selector de arriba y crear **Promociones**.

### Lo más importante

1. **Sin caja abierta no se puede vender.** Siempre abre caja al iniciar el turno.
2. **Cada venta rebaja el inventario** del kiosko automáticamente.
3. **CF** = Consumidor Final (cliente sin NIT). Es lo más común en ventas al mostrador.

---

# Parte 1 — Ventas del Kiosko

Ruta en el menú: **Ventas del Kiosko**

Al entrar verás arriba:
- El **nombre del kiosko** (o un selector si eres admin).
- Tu **nombre**.
- Si la caja está **abierta** (verde) o **cerrada** (gris).
- A veces un aviso amarillo de **depósitos pendientes** (falta registrar la boleta del banco).

Debajo están las **pestañas**.

---

## Pestaña: Resumen

Muestra cómo van las ventas del kiosko en números grandes:

- **Hoy** — cuánto se vendió hoy y cuántas ventas.
- Comparación con el **mismo día del año pasado**.
- **Mes en curso** y **mes anterior**.

Abajo hay una tabla **Ventas por día** con efectivo, tarjeta, depósitos pendientes, etc.

**Úsala cuando:** quieras ver de un vistazo si el kiosko va bien, sin vender nada.

---

## Pestaña: POS (la pantalla de venta)

Aquí es donde **se vende**. Tiene tres zonas: catálogo, carrito y (abajo) consulta de stock en otros kioskos.

### Paso 1 — Verifica que la caja esté abierta

Si ves el mensaje *"Debes abrir caja antes de registrar ventas"*, ve a la pestaña **Caja** y ábrela (explicado más abajo).

### Paso 2 — Buscar y filtrar productos

**Barra de búsqueda:** escribe código, nombre o color.

**Filtros (botones/chips):**

| Filtro | Qué hace |
|--------|----------|
| **Productos / Empaques** | Productos normales o empaques de regalo (códigos SUM-) |
| **Categoría** | Billeteras, bolsos, accesorios, etc. |
| **Línea** | Dama, Caballero o Unisex |
| **Color** | Negro, café, rojo, etc. |

Toca un chip otra vez para **quitar** el filtro.

### Paso 3 — Agregar al carrito

- Toca el **color** del producto en la tarjeta.
- Aparece un número en el chip = cantidad en el carrito.
- Si el color está **gris o deshabilitado**, no hay stock.
- Si dice **"Talla"** (cinchos): toca, elige la talla en la ventana y se agrega al carrito.

**Empaques:** solo se venden si hay stock en el kiosko.

### Paso 4 — Revisar el carrito (lado derecho)

- Cambia la **cantidad** con el cuadro numérico.
- **Quitar** elimina la línea.
- Ves **Subtotal**, **Descuento** (si hay promoción vigente o automática) y **Total**.
- Si hay promoción automática que aplica a los productos del carrito, verás **"Descuento automático aplicado"** sin tener que elegir nada.
- **"¿Hay promoción? Aplicar descuento"** abre la pantalla de cobro para elegir un descuento manual (10/15/20% u otra promoción).
- **"Cobrar Q…"** — botón principal para cobrar.
- **"Cancelar venta"** — vacía el carrito.

### Paso 5 — Cobrar

Se abre la ventana **"Cobrar venta"**.

#### Cliente / factura

| Campo | Qué poner |
|-------|-----------|
| **NIT / CF** | Escribe `CF` para consumidor final, o el NIT del cliente |
| **Consultar NIT** | Si escribiste un NIT válido, busca el nombre automáticamente |
| **Nombre en factura** | Se llena solo al consultar NIT, o queda "CONSUMIDOR FINAL" |
| **Emitir factura electrónica (CF)** | Marca solo si el cliente quiere factura aunque sea CF |

#### Promoción

- Las promociones **vigentes** se aplican **solas** en caja (% , monto fijo, combo y por audiencia + categoría). No hace falta seleccionarlas en cada venta.
- Si varias promos califican, el sistema usa la que más descuento da al cliente.
- Los **empaques (códigos SUM-)** nunca tienen descuento, ni automático ni manual.
- Para casos excepcionales, usa botones rápidos: **10% OFF**, **15% OFF**, **20% OFF** (solo sobre productos, no empaques).
- O busca una promoción guardada escribiendo en el selector (ej. "Liquidación") para **forzar** una campaña concreta.
- Verás **"Descuento aplicado: -Q…"** antes de confirmar.

**Promoción "Por línea":** el administrador define tiers con **audiencia + categoría + %** (ej. Caballero · Billeteras · 15%). El sistema calcula el descuento por producto según su línea y categoría.

#### Forma de pago

Elige **Efectivo**, **Tarjeta** o **Mixto**.

**Efectivo:**
- Escribe cuánto te dio el cliente.
- Atajos: **EXACTO**, **+50**, **+100**, **+200**, **+500**.
- Abajo aparece el **Cambio**.

**Tarjeta:**
- Número de **autorización** (del voucher).
- **Últimos 4 dígitos** de la tarjeta.

**Mixto:** parte en efectivo y parte en tarjeta; indica ambos montos.

#### Confirmar

- **"Confirmar Q…"** — registra la venta.
- Si pide **correo para factura electrónica**, escríbelo o usa "Certificar sin correo".
- Pantalla de éxito: **"Venta registrada"** con número de venta, total y cambio.
- **"Nueva venta"** — listo para el siguiente cliente.

### Consultar stock en otro kiosko

Abajo en la pestaña POS:

1. Busca el **producto / color**.
2. Toca **Consultar**.
3. Ves una tabla con otros kioskos y cuánto tienen.

Útil cuando un cliente pregunta *"¿lo tienen en el otro local?"*

---

## Pestaña: Caja

### Abrir caja (inicio del turno)

1. Toca **"Abrir caja — Q300"**.
2. El fondo inicial es **Q300** (dinero base en el cajón).
3. Aparece **"Caja abierta"** arriba en verde.

### Durante el turno

Ves un resumen:
- Ventas registradas
- Efectivo y tarjeta en ventas
- Efectivo esperado en caja

Si hay ventas en efectivo sin boleta de depósito, verás una **alerta amarilla**.

### Cerrar caja (fin del turno)

1. Idealmente registra antes las **boletas de depósito** pendientes (pestaña Reportes).
2. Toca **"Cerrar caja"**.
3. Cuenta el **efectivo físico** real en el cajón.
4. El sistema muestra si hay **diferencia** respecto a lo esperado.
5. Puedes escribir **notas** (ej. "faltaron Q5 en monedas").
6. **"Confirmar cierre"**.

Después de cerrar **no podrás vender** hasta abrir caja de nuevo.

---

## Pestaña: Inventario (dentro de Ventas)

**Solo consulta** — no mueve stock manualmente.

- Busca por código, nombre, categoría, color o talla.
- Filtra: todos / stock bajo / sin stock.
- Tabla por producto y color con cantidades y estado (**Normal**, **Stock bajo**, **Sin stock**).

**Úsala cuando:** quieras revisar rápido qué hay en el kiosko mientras vendes.

> Para ajustes, traslados o conteos formales, usa el menú **Inventario del Kiosko** (Parte 2).

---

## Pestaña: Recibir distribución

*(Solo si tu usuario tiene permiso)*

Cuando el almacén **envía mercadería** al kiosko:

1. Verás envíos **pendientes**.
2. Toca **"Revisar y confirmar"** en cada envío.
3. Revisa producto, color, cantidad enviada vs recibida.
4. Si falta algo, anota en la **nota de la línea** o en **observación general**.
5. **"Recibir todo conforme"** si todo llegó bien.
6. **"Confirmar recepción"**.

Después de confirmar, esos productos **ya se pueden vender** en el POS.

---

## Pestaña: Reportes de ventas

### Ver ventas

- Elige **fechas** (Inicio / Fin) y **Aplicar filtro**.
- Filtro **Boleta depósito:** Todas o **Pendientes**.
- Tabla con cada venta: fecha, número, cliente, pago, total, factura, depósito.

Toca una fila para ver **detalle completo**.

### Boleta de depósito (efectivo al banco)

Cuando cobras en **efectivo**, el dinero debe depositarse y registrar la boleta:

1. En reportes, busca ventas con badge **Pendiente** en depósito.
2. Abre la venta o toca **"Boleta"**.
3. Escribe el **número de boleta** del banco.
4. **"Registrar boleta"**.

### Anular una venta (error)

Solo si **la caja sigue abierta** y es una venta del **mismo turno**:

1. Abre la venta en reportes.
2. **"Anular venta"**.
3. Escribe el **motivo** (obligatorio).
4. Confirma.

El inventario **vuelve al kiosko** y, si había factura electrónica, se anula también.

---

## Pestaña: Promociones (solo administrador)

Aquí se **crean** las campañas de descuento. La cajera las **aplica al cobrar**, no aquí.

### Tipos de promoción

| Tipo | Significado | Ejemplo |
|------|-------------|---------|
| **Porcentaje (%)** | Descuento % a todo o a una línea | 15% en productos Dama |
| **Porcentaje por línea** | Tiers con audiencia + categoría + % | Caballero · Billeteras · 15%; Dama · Bolsos dama · 10% |
| **Monto fijo (Q)** | Resta un monto en quetzales | Q50 de descuento |
| **Combo (2x1)** | Lleva X unidades, paga Y | Lleva 2, paga 1 |

### Campos al crear

- **Nombre** — cómo la verá la cajera (ej. "Liquidación marzo").
- **Kiosko** — vacío = aplica a todos; o elige uno solo.
- **Línea** — (solo en % simple) todas, Dama o Caballero.
- **Tiers** — (solo en "Porcentaje por línea") agrega filas con audiencia, categoría y %. Al menos una fila con % mayor a cero.
- **Inicio / Fin** — fechas de vigencia (opcional).
- **Crear promoción**.

Los selectores se pueden **buscar escribiendo** (tipo, kiosko, línea).

---

## Tu día en 5 pasos (resumen ventas)

```
1. Abrir caja          → pestaña Caja
2. Vender              → pestaña POS
3. Cobrar              → botón Cobrar → Confirmar
4. Depósitos           → Reportes → registrar boletas
5. Cerrar caja         → pestaña Caja → Cerrar
```

---

# Parte 2 — Inventario del Kiosko

Ruta en el menú: **Inventario del Kiosko**

Pantalla para **personal autorizado** que necesita mover stock, corregir diferencias o hacer conteos. La cajera normal **no** usa esto en el día a día; las ventas del POS ya rebajan stock solas.

Tiene **3 pestañas:**

| Pestaña | Para qué |
|---------|----------|
| **Inventario y movimientos** | Ver stock y registrar entradas, salidas, traslados, etc. |
| **Kardex (periodo)** | Historial contable por fechas |
| **Conteo físico** | Contar mercadería real vs sistema |

Primero elige el **Kiosko para consulta** (puedes buscar escribiendo).

---

## Inventario y movimientos

### Ver stock

Con un kiosko seleccionado, la tabla muestra:
- Producto, color, cantidad **actual**, **mínimo**, estado (**Normal** / **Bajo** en rojo).

Filtros arriba de la tabla: **Todo**, **Productos**, **Empaques**.

### Registrar un movimiento (formulario izquierdo)

Elige **Operación** y completa los campos. Luego **"Registrar movimiento"**.

| Operación | Cuándo usarla |
|-----------|---------------|
| **Entrada de stock** | Llegó mercadería que no vino por distribución, o ajuste manual |
| **Venta** | Registrar venta fuera del POS (caso excepcional) |
| **Cambio de producto** | Cliente devuelve uno y recibe otro |
| **Devolución a depósito** | Producto regresa al depósito/bodega |
| **Devolución de cliente** | Cliente devuelve producto; indicas si está apto para reventa |
| **Traslado entre kioskos** | Mueves unidades de un kiosko a otro |
| **Merma** | Pérdida, daño, robo — baja stock con motivo |
| **Ajuste por conteo físico** | Tras contar, indicas la **cantidad real** y el sistema corrige |
| **Anulación de factura** | Reversa stock por anulación; indicas si el producto salió del kiosko |

Siempre indica **producto**, **color** (si aplica) y **cantidad**.

### Historial

Abajo aparece **"Historial de movimientos"** — lo último que pasó en ese kiosko.

### Botón "Generar inventario en kioskos"

Solo administración técnica: crea registros de stock inicial. **No lo uses** en operación normal sin indicación.

---

## Kardex (periodo)

Para ver **entradas, ventas y salidas** de un rango de fechas:

1. Pestaña **Kardex (periodo)**.
2. Elige kiosko (desde el selector general arriba).
3. Fechas **Desde** / **Hasta**.
4. Genera el reporte.
5. Filtra por producto, línea, tipo de movimiento, etc.
6. Toca una fila para ver el detalle de movimientos.

Útil para auditorías o cuadres con contabilidad.

---

## Conteo físico

Para cuando **cuentas la mercadería real** en el kiosko y la comparas con el sistema:

1. Pestaña **Conteo físico**.
2. Elige **Desde** y **Hasta** (periodo del conteo).
3. **"Abrir conteo"** — crea una sesión nueva.
4. Cuenta por ubicación (V1, V2, … vitrinas, bodega, etc.) y llena las cantidades.
5. **Guardar** según avances.
6. **Marcar como revisado** cuando termines de contar.
7. **Cerrar conteo** cuando esté cuadrado o documentado.

Puedes **exportar a Excel o PDF**.

Si hay diferencias grandes, el sistema puede alertar a correos configurados en **"Destinatarios de alertas"**.

---

# ¿Cuál inventario uso?

| Necesito… | Dónde voy |
|-----------|-----------|
| Ver si hay stock mientras vendo | Ventas del Kiosko → pestaña **Inventario** |
| Vender y que baje solo | Ventas del Kiosko → pestaña **POS** |
| Recibir cajas del almacén | Ventas del Kiosko → **Recibir distribución** |
| Trasladar a otro kiosko, merma, ajuste | **Inventario del Kiosko** → movimientos |
| Conteo mensual formal | **Inventario del Kiosko** → **Conteo físico** |
| Ver historial contable | **Inventario del Kiosko** → **Kardex** |

---

# Preguntas frecuentes

**¿Por qué no me deja vender?**  
Caja cerrada. Abre caja en la pestaña Caja.

**¿Por qué un producto no aparece o está gris?**  
Sin stock en ese color (o talla). Revisa Inventario o consulta otro kiosko.

**¿Qué pongo en NIT?**  
`CF` para la mayoría de ventas al público.

**¿Cuándo uso promoción "Por línea"?**  
Cuando una campaña tiene distintos descuentos según **audiencia y categoría** del producto. El sistema las aplica automáticamente; el cajero solo elige manualmente si quiere **forzar** otra promo o usar 10/15/20%.

**¿Qué es "depósito pendiente"?**  
Vendiste en efectivo y aún no registraste la boleta del depósito bancario.

**¿Puedo anular ayer?**  
No desde aquí si ya cerraste caja. Anulaciones son del turno actual con caja abierta.

**¿Qué es modo piloto?**  
Algunos kioskos en prueba: las ventas se ven localmente pero **no cuentan** en reportes generales de la empresa.

**¿Los empaques SUM- tienen descuento?**  
No. Los empaques nunca reciben descuento (ni promoción automática ni botones 10/15/20%).

**¿Los empaques SUM-?**  
Son bolsas/cajas de regalo. Se venden como producto aparte si hay stock.

---

# Glosario rápido

| Palabra | Significado |
|---------|-------------|
| **POS** | Punto de venta — pantalla donde cobras |
| **CF** | Consumidor final — sin NIT |
| **FEL** | Factura electrónica (SAT Guatemala) |
| **Línea** | Dama, Caballero o Unisex (tipo de producto) |
| **Kardex** | Historial de entradas y salidas de inventario |
| **Merma** | Pérdida de producto (daño, extravío) |
| **Boleta de depósito** | Comprobante del banco al depositar efectivo |
| **Turno / sesión de caja** | Desde que abres caja hasta que la cierras |

---

*Manual para usuarios del POS Kiosko Fossiles. Si algo en pantalla no coincide con este documento, puede haberse actualizado la interfaz — consulta con tu administrador.*
