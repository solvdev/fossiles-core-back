# Manual del Kiosko Fossiles

**Para encargadas, cajeras, supervisoras y administradores**  
Guía sencilla — sin tecnicismos.

> **Solo Ventas del Kiosko (encargada/cajera):** [`MANUAL-VENTAS-KIOSKO-USUARIO.md`](./MANUAL-VENTAS-KIOSKO-USUARIO.md)

---

## ¿Qué es esto?

El sistema te ayuda a **vender en el kiosko**, **controlar la caja**, **ver y mover inventario**, **recibir mercadería del almacén**, y gestionar **devoluciones y cambios** con boleta física.

### Menú Kioscos (tres pantallas)

| Menú | Para qué sirve |
|------|----------------|
| **Ventas del Kiosko** | POS, caja, reportes, recibir distribución, consulta rápida de stock |
| **Inventario del Kiosko** | Stock, movimientos, kardex, conteo físico, traslados (supervisora / admin) |
| **Devoluciones / Reintegros** | Boletas de cambio, devoluciones simples, reintegros a bodega, autorizaciones |

Dentro de **Ventas del Kiosko** hay una pestaña **Inventario** (solo consulta). No es lo mismo que el módulo completo **Inventario del Kiosko**.

---

## Antes de empezar

### ¿Quién ve qué?

| Rol | Qué puede hacer |
|-----|-----------------|
| **Encargada del kiosko** | Ve solo su kiosko: vender, caja, reportes, recibir envíos, devoluciones/cambios de su local |
| **Supervisora de kiosko** | Ve **todos** los kioskos: inventario, distribución, ajustes, reportes, autorizar algunos procesos |
| **Administrador** | Todo lo anterior + promociones, selector de kiosko, configuración |
| **Logística** | Autoriza **cambios sin diferencia de precio** (pestaña Autorizaciones pendientes) |

### Lo más importante

1. **Sin caja abierta no se puede vender.** Abre caja al iniciar el turno.
2. **Cada venta rebaja el inventario** del kiosko automáticamente.
3. **Toda venta genera factura electrónica (FEL).** Por defecto es **CF**; con NIT se factura a nombre del cliente.
4. **CF** = Consumidor Final (cliente sin NIT). Es lo más común en ventas al mostrador.
5. **Boleta física obligatoria** en traslados, devoluciones y cambios (número pre-impreso; el sistema no lo genera solo).

---

# Parte 1 — Ventas del Kiosko

Ruta: **Kioscos → Ventas del Kiosko**

Al entrar se abre la pestaña **POS**. Verás:
- Nombre del **kiosko** (o selector si eres admin/supervisora).
- Tu **nombre**.
- Estado de **caja** (abierta / cerrada).
- Aviso de **depósitos pendientes** si aplica.
- Banner de **modo piloto** si el kiosko está en prueba.

**Pestañas:** Resumen · POS · Caja · **Cierres** · Inventario · Recibir distribución *(si tienes permiso)* · **Reportes de ventas** · Promociones (solo admin)

---

## Pestaña: Resumen

Muestra ventas del kiosko en números grandes:

- **Hoy** — monto y cantidad de ventas (vs mismo día año anterior).
- **Mes en curso** y **mes anterior**.

Tabla **Ventas por día** (mes actual):

| Columna | Significado |
|---------|-------------|
| Ventas / Unidades / Total | Resumen de ventas del día |
| **Efectivo** | Cobrado en efectivo (POS) |
| **Gastos** | Salidas de caja (desembolsos) registradas ese día |
| **Dif. caja** | Diferencia al cerrar caja (si ya cerró ese día) |
| Tarjeta | Cobrado con tarjeta |
| Depósitos pendientes | Ventas en efectivo sin boleta de banco |
| Anuladas / Prueba | Control interno |

**Úsala cuando:** quieras ver de un vistazo cómo va el kiosko sin vender.

---

## Pestaña: POS (venta)

Tres zonas: **catálogo**, **carrito**, **consulta stock en otros kioskos**.

### Antes de vender

Caja **abierta**. Si no: pestaña **Caja → Abrir caja** (el fondo inicial lo define administración por kiosko; suele ser Q300).

### Buscar productos

- Barra de búsqueda: código, nombre, color.
- Filtros: Productos/Empaques, Categoría, Línea (Dama/Caballero/Unisex), Color.

### Agregar al carrito

- Toca el **color** en la tarjeta del producto (verás la cantidad disponible).
- Algunos productos tienen **herraje Nuevo** y **Viejo**: chips separados (`Negro · Nuevo` / `Negro · Viejo`).
- **Talla** (cinchos): elige talla en la ventana.
- Color **gris** / Sin stock = no se puede agregar.
- Empaques **SUM-**: solo si hay stock; **sin descuento**.
- **Miraflores (A15):** puedes editar precio unitario (**Con desc.** / **Final**); se factura ese total sin descuento adicional encima.

### Carrito y cobro

- Subtotal, descuento (automático o manual), total.
- **Cobrar Q…** → ventana **Cobrar venta**.
- Botón final: **Confirmar y facturar Q…** — guarda la venta **y** certifica la factura electrónica (FEL).
- Por defecto aplica **10%**; promociones vigentes se aplican solas; botones **10% OFF / 15% OFF / 20% OFF** para excepciones.
- Checkbox **Cobrar sin descuento (precio normal)** cuando el cliente paga precio de catálogo sin descuento ni promoción.
- Datos de factura: NIT/CF, consultar NIT, nombre; **correo** y **teléfono** opcionales. **Siempre se factura** (no hay opción de no emitir).
- Si FEL falla: la venta queda; usa **Certificar factura** en el aviso o en el detalle de Reportes.

### Formas de pago

| Método | Qué registrar |
|--------|----------------|
| **Efectivo** | Monto recibido → calcula cambio (atajos **EXACTO**, +50/+100/+200/+500) |
| **Tarjeta** | Marca, **Número de voucher**, últimos 4 dígitos, **Monto del voucher** |
| **Tarjeta (2 tarjetas)** | Checkbox **Dividir pago en dos tarjetas** → montos y datos de voucher de cada tarjeta |
| **Mixto** | Parte efectivo + parte tarjeta (una sola tarjeta) + voucher de la tarjeta |

Si el monto del voucher no coincide con la factura, el sistema avisa la diferencia: **la factura no se modifica**.

#### Pago con dos tarjetas

Cuando el cliente divide el total entre **dos tarjetas** (no es efectivo + tarjeta):

1. Forma de pago **Tarjeta**.
2. Activa **Dividir pago en dos tarjetas**.
3. **Monto tarjeta 1** y **monto tarjeta 2** (suma = total).
4. Marca, **número de voucher**, **últimos 4** y **monto del voucher** de **cada** tarjeta.
5. **Confirmar y facturar**.

Resultado:
- **Una venta** y **una factura**.
- En **Reportes de ventas → Ventas**, la forma de pago muestra Tarjeta 1 y Tarjeta 2.
- En **Reportes de ventas → Voucher (tarjeta)**, **dos filas** (un voucher por tarjeta) para conciliar con el banco.

---

### Consultar otro kiosko

Abajo en POS: busca producto/color → **Consultar** → tabla con stock en otros locales.

---

## Pestaña: Caja

### Abrir caja

**Abrir caja** → el sistema registra el **fondo inicial** configurado para tu kiosko (por ejemplo Q300).

### Durante el turno — Cuadre de efectivo

El sistema calcula:

```
Efectivo esperado = fondo inicial
                  + efectivo en ventas del turno
                  − desembolsos del turno (todos)
```

En pantalla ves:
- Fondo inicial, efectivo en ventas, **desembolsos del turno**, **efectivo esperado**.
- Ventas en tarjeta (no van al cajón de efectivo).

### Registrar desembolsos (gastos de efectivo)

Cuando **sales efectivo del turno** para una compra (insumos, taxi, etc.), regístralo como **desembolso** desde el **detalle de la venta** (no desde la pestaña Caja; ahí solo ves el cuadre):

1. **Reportes de ventas** → toca la venta (efectivo o mixto del turno).
2. Bloque **Desembolsos de esta venta** → **Monto** + **Descripción**.
3. **Agregar desembolso**.

**Ejemplo:** venta en efectivo Q500 → desembolso Q50 ligado a esa venta → **a depositar en banco Q450** (no Q500).

Los desembolsos **restan** del efectivo esperado de caja y del **depósito neto** de esa venta.

**Importante:** solo puedes ligar desembolsos a ventas **del mismo turno** (caja abierta), en **efectivo o mixto**, y el total ligado no puede superar el efectivo de esa venta.

### Cerrar caja

1. Registra **boletas de depósito** pendientes (Reportes de ventas) si aplica.
2. **Cerrar caja**.
3. Cuenta el **efectivo físico** real.
4. El sistema muestra **diferencia** (contado − esperado): sobra o falta.
5. Notas opcionales → **Confirmar cierre**.

Después de cerrar **no se vende** hasta abrir caja de nuevo. Se abre un **reporte de cierre** (ver en pantalla o en pestaña **Cierres**).

---

## Pestaña: Cierres

Historial de **turnos ya cerrados** (no es la caja del día en curso).

- Filtra por **fechas** (y por kiosko si eres admin).
- Tabla: apertura, cierre, fondo, ventas efectivo/tarjeta, desembolsos, efectivo contado, **diferencia**.
- Acciones: **Ver reporte**, **PDF**, **Excel** del cierre.

**Úsala cuando:** necesites reimprimir o revisar un cierre pasado sin abrir contabilidad.

---

## Pestaña: Inventario (consulta en Ventas)

**Solo lectura** para consulta rápida — no mueve stock.

- Busca por código, nombre, categoría, color, talla.
- Filtros: todos / stock bajo / sin stock.
- Estados: Normal, Stock bajo, Sin stock.

### Mi conteo (control interno)

Pestaña secundaria **Mi conteo** dentro de Inventario (Ventas):

- Conteo **solo por vitrinas** para tu control diario.
- **No reemplaza** el conteo físico oficial (Inventario del Kiosko → Conteo físico).
- Puedes **guardar borrador**, **guardar snapshot del día** y exportar **Excel**.

Para **ajustes, traslados o conteo oficial**, usa **Inventario del Kiosko** (Parte 2).

---

## Pestaña: Recibir distribución

Cuando el almacén **envía mercadería**:

1. Envíos en estado **pendientes** (destino = tu kiosko).
2. **Revisar y confirmar** cada envío.
3. Compara cantidad enviada vs recibida; anota faltantes.
4. **Recibir todo conforme** o **Confirmar recepción** con cantidades parciales.

Tras confirmar, el stock **ya se puede vender** en POS.

*(Encargada: recepción simple desde aquí. Supervisora: también puede usar **Distribución → Confirmación de recepción** con más detalle.)*

---

## Pestaña: Reportes de ventas

Arriba elige el **tipo de reporte**:

| Tipo | Para qué sirve |
|------|----------------|
| **Ventas** | Listado de ventas del periodo (uso diario) |
| **Desembolsos** | Todos los gastos de caja; columna **Venta** (número de la venta ligada) |
| **Depósitos bancarios** | Boletas registradas con **efectivo bruto**, **desembolsos** y **depósito neto** |
| **Voucher (tarjeta)** | Ventas con tarjeta para conciliar vouchers; **dos filas** si la venta usó 2 tarjetas |
| **Hoja principal** | Resumen por **corte de conteo físico** (supervisora / cierre de periodo) |

Todos permiten **Excel** y **PDF** según el tipo.

### Filtrar ventas (tipo Ventas)

- Fechas **Inicio / Fin** → **Aplicar filtro**.
- Atajos: Hoy, Ayer, Esta semana, Este mes.
- Filtro **Boleta depósito:** Todas o **Pendientes**.
- Toca una fila → **detalle** de la venta.
- Botón **Boleta** en filas pendientes → abre detalle para registrar boleta.

### Cuadre con caja (turno abierto)

Si la caja está abierta, verás un recuadro **Cuadre de caja (turno abierto)** comparando:
- Efectivo en ventas del turno (caja).
- Desembolsos registrados.
- Efectivo esperado.

Compara con la columna **Efectivo** de las ventas del filtro.

### Boleta de depósito y depósito neto

Ventas en **efectivo o mixto** con efectivo > 0 pueden requerir **boleta de depósito** en banco.

En el **detalle de la venta** verás:

| Dato | Significado |
|------|-------------|
| **Efectivo** | Parte en efectivo de la venta (bruto) |
| **Desembolsos** | Suma de desembolsos ligados a esa venta |
| **A depositar / Depósito neto** | Efectivo − desembolsos de la venta |

Al registrar la boleta, el monto a depositar es el **neto**, no el bruto.

**Casos especiales:**
- Si desembolsaste **todo** el efectivo de la venta → **no requiere boleta** (mensaje en detalle).

Pasos: Reportes de ventas → venta con badge **Pendiente** → detalle → **Registrar boleta** → número del comprobante bancario.

### Desembolsos desde el detalle de venta

En ventas en efectivo/mixto, con **caja abierta** del mismo turno:

1. Abre el detalle (Reportes de ventas → clic en la fila).
2. Bloque **Desembolsos de esta venta** → monto + descripción → **Agregar desembolso**.
3. El resumen **A depositar** se actualiza al instante.

### Anular venta

Solo con **caja abierta** y venta del **mismo turno**:
Detalle → **Anular venta** → motivo obligatorio. El stock **regresa** al kiosko.

### Exportar

Botones **Excel** y **PDF** del reporte activo (ventas, desembolsos, depósitos, vouchers o hoja principal).

---

## Pestaña: Promociones (solo administrador)

Crear campañas de descuento:

| Tipo | Uso |
|------|-----|
| **Porcentaje (%)** | Descuento global o por línea |
| **Porcentaje por línea** | Tiers: audiencia + categoría + % |
| **Monto fijo (Q)** | Resta quetzales |
| **Combo (2x1)** | Lleva X, paga Y |

Campos: nombre, kiosko (vacío = todos), fechas vigencia, línea/tiers.

---

## Tu día en 7 pasos (encargada)

```
1. Abrir caja           → Caja
2. Vender / facturar    → POS
3. Desembolsos          → Reportes de ventas → detalle de la venta
4. Depósitos banco      → Reportes de ventas → boletas (monto NETO a depositar)
5. Mi conteo (opcional) → Inventario → Mi conteo
6. Devoluciones/cambios → Devoluciones / Reintegros (si aplica)
7. Cerrar caja          → Caja → contar efectivo y confirmar
```

---

# Parte 2 — Inventario del Kiosko

Ruta: **Kioscos → Inventario del Kiosko**

Para **supervisora, logística o administración**. La encargada normal **no** usa esto en el día a día (el POS rebaja stock solo).

**3 pestañas:** Inventario y movimientos · Kardex (periodo) · Conteo físico

Elige primero el **Kiosko para consulta** (buscador arriba).

---

## Inventario y movimientos

### Ver stock

Tabla: producto, color, cantidad **actual**, **mínimo**, estado (Normal / Bajo).

Filtros: Todo · Productos · Empaques.

### Registrar movimiento (formulario izquierdo)

| Operación | Cuándo usarla |
|-----------|---------------|
| **Entrada de stock** | Mercadería que no vino por distribución |
| **Venta** | Venta fuera del POS (excepcional) |
| **Cambio de producto** | Cambio manual en kardex (preferir módulo Devoluciones) |
| **Devolución a depósito** | Producto regresa a bodega |
| **Devolución de cliente** | Cliente devuelve; indicar si apto |
| **Traslado entre kioskos** | Mueve stock — **boleta física obligatoria** |
| **Merma** | Daño, pérdida, robo |
| **Ajuste por conteo físico** | Tras contar, indicas cantidad real |
| **Anulación de factura** | Reversa por anulación FEL |

Completa producto, color, cantidad (y talla en cinchos). **Registrar movimiento**.

### Historial

Abajo: **Historial de movimientos** del kiosko seleccionado.

Referencia en kardex: prioriza **número de boleta física** cuando existe.

### Generar inventario en kioskos

Solo administración técnica (stock inicial). No usar en operación normal sin indicación.

---

## Kardex (periodo)

1. Pestaña **Kardex (periodo)**.
2. Kiosko + fechas **Desde / Hasta**.
3. Generar reporte.
4. Filtrar por producto, línea, tipo de movimiento.
5. Detalle por fila.

Para auditoría y cuadre con contabilidad.

---

## Conteo físico

1. Pestaña **Conteo físico**.
2. Periodo **Desde / Hasta**.
3. **Abrir conteo** → sesión nueva.
4. Cuenta por ubicación (V1…V7, vitrinas E, bodega BO).
5. **Guardar** → **Terminar conteo físico** (bloquea vitrinas) → **Marcar como revisado** → **Cerrar conteo**.
6. Exportar **Excel / PDF** (incluye kardex, diferencias y leyenda de colores).

**Subconteo al corte:** elige una fecha → inventario del sistema a las 23:59 de ese día (sin vitrinas) → Excel/PDF al corte.

**Export Excel:** nombres compactos en columna Producto (nombre, color abreviado, talla en cinchos; código solo en billeteras). Columna **Código** aparte sigue completa.

Diferencias grandes → alertas a correos en **Destinatarios de alertas**.

> **Mi conteo** (pestaña Inventario en Ventas) es solo control interno de la encargada; el conteo **oficial** es este.

---

# Parte 3 — Devoluciones / Reintegros

Ruta: **Kioscos → Devoluciones / Reintegros**

Gestiona **devoluciones simples** y **boletas de cambio** con trazabilidad. Siempre necesitas el **número de boleta física** impresa.

Botones principales:
- **Boleta de cambio** — cliente devuelve un producto y recibe otro.
- **Devolución** — cliente devuelve sin comprar otro producto en el acto.

Elige **Kiosko** arriba (encargada: suele ser solo el suyo).

### Pestañas

| Pestaña | Contenido |
|---------|-----------|
| **Boletas de cambio** | Historial de cambios (número, venta orig., productos, diferencia, estado) |
| **Devoluciones** | Devoluciones simples registradas |
| **Reintegros pendientes** | Productos aptos devueltos que aún no regresaron a bodega PT |
| **Autorizaciones pendientes** | Solo logística/supervisora — cambios sin cobro pendientes de aprobar |

---

## Devolución simple

Asistente **Devolución**:

1. **Número de venta POS** original → Buscar.
2. Elige la **línea** devuelta y cantidad.
3. **¿Apto para reventa?** Sí / No.
4. **Motivo** (obligatorio).
5. **Número de boleta física** de devolución (obligatorio).
6. Confirmar → imprime comprobante.

**Si apto:** queda **Pendiente reintegro** — el producto entró al kiosko pero debe **reintegrarse** a Bodega PT (pestaña Reintegros pendientes → **Reintegrar**).

**Si no apto:** queda completado (no reventa).

El stock del kiosko **sube** al registrar la devolución.

---

## Boleta de cambio

Asistente **Boleta de cambio** (4 pasos):

### Paso 1 — Venta original

Número de venta POS → **Buscar**.

### Paso 2 — Qué devuelve el cliente

Selecciona **línea** de la venta y **cantidad devuelta**.

### Paso 3 — Qué entrega el kiosko

Busca producto nuevo con stock → elige fila (producto, color, talla si es cincho).

### Paso 4 — Resumen

Ves tres bloques:

| Bloque | Significado |
|--------|-------------|
| **INGRESO** | Producto devuelto — valor a crédito (precio que pagó el cliente) |
| **EGRESO** | Producto nuevo — valor de salida |
| **DIFERENCIA** | EGRESO − INGRESO (lo que cobras o Q 0.00) |

**Número de boleta física de cambio** (obligatorio).

---

### Reglas de precio en cambios

El sistema usa el **precio realmente pagado** en la venta original (incluye descuentos de promoción).

| Caso | Diferencia de precio |
|------|----------------------|
| **Mismo producto** (fallo, defecto, mismo código) | **Q 0.00** — aunque el catálogo haya subido o hubo descuento |
| **Cincho — solo cambio de talla** | **Q 0.00** — conserva lo que pagó con descuento |
| **Producto diferente** | Diferencia = precio catálogo del nuevo − precio pagado del devuelto |

---

### Cambio CON diferencia de precio (diferencia > Q 0.00)

1. **Cobrar y confirmar** → cobro como mini-venta POS (efectivo/tarjeta/mixto, NIT, factura si aplica).
2. Se mueve inventario (ingreso devuelto + egreso nuevo).
3. Estado **Completado**.
4. **Imprimir** boleta desde la lista.

---

### Cambio SIN diferencia (diferencia = Q 0.00)

1. **No hay cobro ni facturación.**
2. Indica **motivo** (ej. cambio de talla, fallo de fábrica).
3. **Enviar solicitud de cambio**.
4. Estado **Pendiente autorización**.
5. **Logística** (pestaña Autorizaciones pendientes) → **Autorizar** o **Rechazar**.
6. Al autorizar: inventario se mueve y pasa a **Completado**.

---

## Reintegro a bodega

Devoluciones **aptas** quedan en **Reintegros pendientes**:

1. Pestaña **Reintegros pendientes**.
2. **Reintegrar** en la fila correspondiente.
3. El producto sale del kiosko hacia **Bodega PT** (devolución depósito).

---

## Autorizaciones (logística / supervisora)

Pestaña **Autorizaciones pendientes**:

- Lista cambios sin diferencia esperando aprobación.
- **Autorizar** — aplica movimiento de inventario.
- **Rechazar** — indica motivo; no mueve stock.

---

## Impresión

En las listas de boletas de cambio y devoluciones: botón **Imprimir** para reimprimir el comprobante.

---

# ¿Qué pantalla uso?

| Necesito… | Dónde voy |
|-----------|-----------|
| Vender | Ventas → **POS** |
| Abrir/cerrar caja, desembolsos, cuadre | Ventas → **Caja** |
| Historial de cierres (PDF/Excel) | Ventas → **Cierres** |
| Ver ventas / desembolsos / depósitos | Ventas → **Reportes de ventas** |
| Mi conteo interno (vitrinas) | Ventas → **Inventario** → **Mi conteo** |
| Ver ventas del día / KPIs | Ventas → **Reportes de ventas** o **Resumen** |
| Consulta rápida de stock | Ventas → **Inventario** |
| Recibir cajas del almacén | Ventas → **Recibir distribución** |
| Cambio de producto con boleta | **Devoluciones / Reintegros** → Boleta de cambio |
| Devolución sin compra nueva | **Devoluciones / Reintegros** → Devolución |
| Reintegrar a bodega | **Devoluciones / Reintegros** → Reintegros pendientes |
| Autorizar cambio sin cobro | **Devoluciones / Reintegros** → Autorizaciones pendientes |
| Traslado, merma, ajuste | **Inventario del Kiosko** → movimientos |
| Conteo mensual | **Inventario del Kiosko** → Conteo físico |
| Historial contable | **Inventario del Kiosko** → Kardex |

---

# Preguntas frecuentes

**¿Por qué no me deja vender?**  
Caja cerrada. Abre caja en la pestaña Caja.

**¿Por qué un producto está gris en POS?**  
Sin stock en ese color o talla.

**¿Qué pongo en NIT?**  
`CF` para la mayoría de ventas al público. Toda venta se factura (FEL).

**¿Qué es depósito pendiente?**  
Vendiste en efectivo (o mixto con efectivo) y falta registrar la boleta del banco. El monto a depositar es el **neto** (efectivo de la venta menos desembolsos ligados a esa venta).

**¿Dónde registro un desembolso?**  
En **Reportes de ventas → detalle de la venta** (la pestaña Caja solo muestra el cuadre; ya no captura desembolsos “generales”).

**¿Deposité el efectivo bruto pero había un desembolso de la venta?**  
El reporte **Depósitos bancarios** muestra bruto, desembolsos y neto. Lo correcto es depositar el **neto**. Si ya depositaste de más, coordina con contabilidad.

**¿Cuándo no pide boleta de depósito?**  
Ventas solo tarjeta, ventas anuladas, o ventas cuyo efectivo fue **totalmente desembolsado** (neto = 0).

**¿Puedo anular la venta de ayer?**  
No desde POS si ya cerraste caja. Solo ventas del turno actual con caja abierta.

**¿Debo registrar desembolsos?**  
Sí, si sacaste efectivo de una venta del turno. Si no los registras, al cerrar caja parecerá que **falta dinero** y el depósito neto quedará mal.

**¿Cómo cuadra el efectivo?**  
Esperado = fondo inicial + ventas en efectivo − **desembolsos del turno**. Al cerrar, comparas con el conteo físico del cajón.

**Cambio de cincho por talla con descuento — cobro diferencia?**  
No. Mismo precio pagado → diferencia Q 0.00 → solicitud y autorización logística.

**Mismo producto por fallo — cobro diferencia?**  
No. Diferencia Q 0.00 aunque hubiera descuento en la venta original.

**¿Empaques SUM- tienen descuento?**  
No.

**¿Modo piloto?**  
Algunos kioskos en prueba: ventas locales que no suman en reportes generales.

**¿Quién autoriza cambios sin cobro?**  
Usuario con permiso de logística (pestaña Autorizaciones pendientes).

---

# Glosario

| Palabra | Significado |
|---------|-------------|
| **POS** | Punto de venta — pantalla donde cobras |
| **CF** | Consumidor final — sin NIT |
| **FEL** | Factura electrónica (SAT Guatemala) |
| **Línea** | Dama, Caballero o Unisex |
| **Kardex** | Historial de entradas y salidas de inventario |
| **Merma** | Pérdida de producto (daño, extravío) |
| **Boleta de depósito** | Comprobante del banco al depositar efectivo (sobre el monto **neto** de la venta) |
| **Depósito neto** | Efectivo de la venta − desembolsos ligados a esa venta |
| **Desembolso** | Gasto de efectivo del turno ligado a una venta |
| **Voucher** | Número/monto del comprobante de la terminal de tarjeta |
| **Herraje Nuevo / Viejo** | Variante de stock del mismo color en POS |
| **Boleta física** | Número del comprobante impreso (traslado, devolución, cambio) |
| **Turno / sesión de caja** | Desde abrir caja hasta cerrarla |
| **Reintegro** | Enviar producto devuelto (apto) de vuelta a bodega PT |
| **INGRESO / EGRESO** | En cambio: producto que entra vs producto que sale del kiosko |
| **Bodega PT** | Bodega de producto terminado |

---

*Manual del módulo Kiosko Fossiles (Ventas, Inventario, Devoluciones y Cambios). Si algo en pantalla no coincide, consulta con tu administrador.*
