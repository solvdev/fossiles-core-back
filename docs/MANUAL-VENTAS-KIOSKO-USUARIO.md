# Manual — Ventas del Kiosko

**Para encargadas y cajeras**  
Pantallas: **Kioscos → Ventas del Kiosko** y **Kioscos → Devoluciones / Reintegros**

> Manual completo del módulo kiosko (inventario, kardex, conteos, etc.): ver [`MANUAL-POS-KIOSKO-USUARIO.md`](./MANUAL-POS-KIOSKO-USUARIO.md)

---

## ¿Qué hace esta pantalla?

Aquí **vendes**, **abres y cierras caja**, **ves reportes**, **recibes mercadería** del almacén (si tienes permiso) y **consultas stock** sin salir del POS.

Al entrar se abre la pestaña **POS** (lista para cobrar).

**Cambios y devoluciones** se hacen en el menú hermano **Devoluciones / Reintegros** (misma área de Kioscos). Este manual también las explica más abajo.

**No incluye:** ajustes de inventario, traslados ni conteo oficial de supervisora (otros menús de Kioscos / Inventario del Kiosko).

---

## Al entrar

Verás arriba:
- **Nombre del kiosko** (solo el tuyo; admin/supervisora puede elegir otro).
- Tu **nombre**.
- **Caja abierta** (verde) o **cerrada** (gris).
- Aviso amarillo si hay **depósitos pendientes** (falta boleta del banco), por ejemplo: `2 depósitos pendientes`.
- Si el kiosko está en **modo piloto**: banner amarillo — las ventas se ven en este resumen, pero **no cuentan** en reportes corporativos.

### Pestañas (Ventas del Kiosko)

| Pestaña | Para qué |
|---------|----------|
| **Resumen** | Ventas del mes, comparativos, tabla por día |
| **POS** | Cobrar al cliente *(pestaña inicial)* |
| **Caja** | Abrir/cerrar turno y ver cuadre de efectivo |
| **Cierres** | Historial de cierres pasados (PDF/Excel) |
| **Inventario** | Ver stock + **Mi conteo** (control interno) |
| **Recibir distribución** | Confirmar envíos del almacén *(solo si tienes permiso)* |
| **Reportes de ventas** | Ventas, desembolsos, depósitos bancarios, vouchers, hoja principal |
| **Promociones** | Solo administrador — crear descuentos |

### Pantallas de cambios / devoluciones

Ruta: **Kioscos → Devoluciones / Reintegros**

| Pestaña | Para qué |
|---------|----------|
| **Boletas de cambio** | Historial de cambios (productos, diferencia, estado) |
| **Devoluciones** | Devoluciones simples (cliente regresa sin llevar otro producto) |
| **Reintegros pendientes** | Productos aptos que aún deben ir a Bodega PT |
| **Autorizaciones pendientes** | Logística / supervisora — aprobar o rechazar cambios sin cobro |

---

## Reglas básicas

1. **Sin caja abierta no vendes.** Abre caja al empezar el día.
2. **Cada venta baja el inventario** del kiosko sola (según el stock que ves en POS).
3. **Toda venta genera factura electrónica (FEL).** Por defecto es **CF**; si el cliente trae NIT, lo consultas y facturas a su nombre.
4. **CF** = consumidor final (sin NIT). Es lo usual.
5. **Empaques SUM-** no llevan descuento.
6. En **cambios y devoluciones** la **boleta física** (número preimpreso) es **obligatoria**. El sistema no la inventa.

---

## Resumen

Números grandes:
- **Hoy** — ventas de hoy vs mismo día año pasado.
- **Mes en curso** y **mes anterior**.

Tabla **Ventas por día** (columnas principales):

| Columna | Qué es |
|---------|--------|
| Ventas / Unidades / Total | Actividad del día |
| Efectivo | Cobrado en efectivo en POS |
| Gastos | Desembolsos (efectivo que salió de caja) |
| Dif. caja | Sobrante o faltante al cerrar (si ya cerró) |
| Tarjeta | Ventas con tarjeta |
| Anuladas | Ventas anuladas ese día |
| Depósitos pendientes | Efectivo sin boleta de banco registrada |
| Prueba | Solo si el kiosko está en modo piloto |

---

## POS — Vender

### 1. Caja abierta

Si el POS está bloqueado → **Caja → Abrir caja** (fondo inicial configurado por kiosko; suele ser Q300). El botón muestra el monto, por ejemplo **Abrir caja — Q 300.00**.

### 2. Buscar producto

- Escribe código, nombre o color.
- Filtros: **Productos** / **Empaques**, Categoría, Línea, Color.

### 3. Agregar al carrito

- Toca el **color** del producto (verás la cantidad disponible).
- Algunos productos tienen **herraje Nuevo** y **Viejo**: aparecen como chips separados (`Negro · Nuevo`, `Negro · Viejo`). Elige el correcto.
- **Cinchos:** elige **talla** en la ventana (muestra cuánto hay de cada talla).
- Gris / **Sin stock** = no se puede agregar.
- En el carrito puedes **Cancelar venta** para vaciar todo.

**Miraflores (A15):** puedes editar el precio unitario en el carrito. Usa las etiquetas **Con desc.** / **Final** según lo acordado; al cobrar se factura exactamente ese total (sin descuento adicional encima).

### 4. Cobrar

- Revisa subtotal, descuento y total.
- **Cobrar Q…** → ventana **Cobrar venta**.
- Al confirmar: **Confirmar y facturar Q…** — se guarda la venta **y** se certifica la factura electrónica.

#### Cliente / factura

| Campo | Qué poner |
|-------|-----------|
| NIT / CF | `CF` casi siempre (botón rápido **CF**) |
| Consultar NIT | Si el cliente trae NIT válido |
| Nombre en factura | Se llena al consultar NIT; con CF suele ser Consumidor Final |
| Correo para factura | Opcional |
| Teléfono / celular | Opcional |

No hay checkbox de “emitir o no factura”: **siempre se factura**.

Si la certificación FEL falla, la venta **queda guardada**. Verás un aviso *Venta pendiente de certificar FEL* y el botón **Certificar factura** (también en el detalle de la venta en Reportes). Completa correo/teléfono si aplica y reintenta. Si pasan varios días, SAT puede bloquear la certificación — avisa a administración.

#### Descuentos

- Por defecto aplica **10%** sobre precio de catálogo (empaques no).
- Promociones vigentes se aplican **solas** cuando corresponden.
- Botones **10% OFF / 15% OFF / 20% OFF** para casos excepcionales.
- **Cobrar sin descuento (precio normal):** checkbox en la ventana de cobro si el cliente paga **precio de catálogo** sin descuento ni promoción.
- Empaques **nunca** tienen descuento.

#### Pago

| Forma | Datos |
|-------|-------|
| **Efectivo** | Cuánto te dio → ves el **cambio**. Atajos: **EXACTO**, **+50 / +100 / +200 / +500** |
| **Tarjeta** | Marca (VISA / Mastercard / AMEX), **Número de voucher**, **Últimos 4 dígitos**, **Monto del voucher** |
| **Tarjeta (2 tarjetas)** | Activa **Dividir pago en dos tarjetas** → monto de cada una + datos de ambas |
| **Mixto** | Parte efectivo + parte tarjeta (una sola tarjeta): montos, recibido en efectivo, cambio, y datos de voucher de la tarjeta |

Si el **monto del voucher** no coincide con lo facturado, el sistema avisa la diferencia: la **factura no se modifica**; el voucher queda con el monto que registraste (útil para conciliar con el banco).

#### Pago con dos tarjetas (misma venta)

A veces el cliente paga **con dos tarjetas** (no es mixto efectivo + tarjeta). En **Tarjeta**:

1. Marca **Dividir pago en dos tarjetas**.
2. Indica **monto tarjeta 1** y **monto tarjeta 2** (deben sumar el total; al poner la primera, la segunda se calcula sola).
3. Completa **marca, número de voucher, últimos 4 y monto del voucher** de **cada** tarjeta.
4. **Confirmar y facturar** → queda **una sola venta**, pero en reportes aparecen **dos vouchers**.

En el listado de ventas y en el detalle verás el detalle de **ambas** tarjetas (Tarjeta 1 y Tarjeta 2).

**Confirmar y facturar** → pantalla de éxito con número de venta.

### Consultar otro kiosko

Abajo del POS: busca producto → **Consultar** → stock en otros locales.

---

## Caja

### Abrir

**Abrir caja** = fondo inicial en el cajón (monto definido por administración).

### Cuadre durante el turno

```
Efectivo esperado = fondo inicial + ventas en efectivo − desembolsos del turno
```

En pantalla: **Fondo inicial**, **Efectivo en ventas**, **Desembolsos (gastos) del turno**, **Efectivo esperado en caja**.

### Registrar desembolsos

Los desembolsos **ya no se capturan en la pestaña Caja** (ahí solo ves el resumen).

Si sacaste efectivo del cajón ligado a una venta:

1. Ve a **Reportes de ventas** → toca la venta (efectivo o mixto).
2. En el detalle: **Monto** + **Descripción** → **Agregar desembolso**.
3. El desembolso se descuenta del **depósito neto** de esa venta (ej. venta Q500 − gasto Q50 → depositar Q450).

Si no registras desembolsos cuando sacaste efectivo, al cerrar parecerá que **falta dinero**.

### Cerrar

1. Registra **boletas de depósito** en Reportes de ventas (ventas en efectivo).
2. **Cerrar caja**.
3. Cuenta el efectivo **físico**.
4. Revisa la **diferencia** (sobra / falta).
5. Notas opcionales → **Confirmar cierre**.

No vendes más hasta abrir caja otra vez.

---

## Inventario (pestaña en Ventas)

**Consulta** de stock — no modifica inventario.

- Primero ves un **resumen por categoría/línea**; luego el detalle.
- Busca producto, color, talla o herraje (Nuevo/Viejo).
- Filtros: todos / stock bajo / sin stock.
- En el detalle, cuando aplica, verás unidades **Nuevo** y **Viejo** por separado.

**Mi conteo:** sub-pestaña para contar vitrinas solo como control personal (no reemplaza el conteo oficial de supervisora).

---

## Cierres

Historial de turnos **ya cerrados**. Ver reporte, PDF o Excel de cada cierre.

---

## Recibir distribución

*(Pestaña visible solo si tienes permiso de confirmación de recepción.)*

Cuando llega mercadería del almacén:

1. Aparecen envíos **pendientes** para tu kiosko (puede haber un contador en la pestaña).
2. **Revisar y confirmar**.
3. Revisa cantidades; anota faltantes si hay.
4. **Recibir todo conforme** (si llegó completo) o **Confirmar recepción** con cantidades parciales.

Después ya puedes **vender** esos productos en POS.

---

## Reportes de ventas

Elige **tipo de reporte:** Ventas · Desembolsos · Depósitos bancarios · Voucher (tarjeta) · Hoja principal.

### Ver ventas

- Modo **Día** o **Rango**; atajos: Hoy, Ayer, Esta semana, Este mes, etc.
- Fechas → **Aplicar filtro**.
- Filtro depósito: **Todas** o **Pendientes**.
- Toca una fila → **detalle** (desembolsos, boleta, anular, certificar FEL si quedó pendiente).

### Cuadre con caja

Con caja abierta: recuadro con efectivo del turno, desembolsos y esperado en caja.

### Boleta de depósito (monto neto)

1. Ventas en efectivo o mixto → depositar en banco el **neto** (efectivo − desembolsos de esa venta).
2. Reportes → **Pendiente** → detalle → ves **Efectivo · Desembolsos · A depositar**.
3. **Registrar boleta** con número del banco.

Si el efectivo de la venta fue **totalmente desembolsado**, no pide boleta.

### Anular venta

Solo si **caja abierta** y venta del **mismo turno**:
Detalle → **Anular venta** → motivo. El stock **vuelve** al kiosko.

### Excel / PDF

Exporta el reporte del tipo seleccionado (en Ventas también consolidado o separado por día).

### Reporte Voucher (tarjeta)

Lista las ventas con tarjeta para conciliar con el banco. Si una venta se cobró con **dos tarjetas**, aparecen **dos filas** (un voucher por tarjeta), ligadas a la **misma venta** y factura.

En el reporte **Ventas**, la columna de pago muestra el detalle de **ambas** tarjetas cuando aplica.

---

## Promociones (solo admin)

Crear campañas: %, % por línea, monto fijo, combo.  
La cajera **no crea** promos aquí; las **aplica al cobrar** en POS.

---

## Cambios y devoluciones

Ruta: **Kioscos → Devoluciones / Reintegros**  
Elige tu **kiosko** arriba. Siempre lleva a mano la **boleta física** impresa.

Hay dos flujos distintos:

| Situación | Qué usar |
|-----------|----------|
| Cliente **devuelve** un producto y **se lleva otro** | **Boleta de cambio** |
| Cliente **solo devuelve** (no se lleva otro en el acto) | **Devolución** |

---

### Devolución (sin cambiar por otro producto)

1. Botón **Devolución**.
2. Busca la **venta POS** original (número de venta).
3. Elige la **línea** y la cantidad que regresa.
4. Indica si el producto está **apto para reventa** (Sí / No).
5. Escribe el **motivo** (obligatorio).
6. Captura el **número de boleta física** de devolución (obligatorio).
7. Confirma → imprime el comprobante.

**Qué pasa con el stock**

- Al registrar, el producto **entra** al inventario del kiosko.
- Si quedó **apto:** estado **Pendiente reintegro**. Luego alguien de logística/encargada debe **Reintegrar** (pestaña **Reintegros pendientes**) para enviarlo a **Bodega PT**.
- Si **no está apto:** queda completado (no se reintegra para reventa).

---

### Boleta de cambio

Botón **Boleta de cambio**. El asistente tiene 4 pasos:

1. **Venta original** — número de venta POS → Buscar.
2. **Qué devuelve el cliente** — línea de la venta y cantidad.
3. **Qué entrega el kiosko** — producto(s) con stock (color, talla si es cincho, herraje Nuevo/Viejo si aplica). Puede ser **uno o varios** productos entregados.
4. **Resumen** — revisa montos y escribe el **número de boleta física de cambio** (obligatorio).

En el resumen verás:

| Bloque | Significado |
|--------|-------------|
| **INGRESO** | Lo que el cliente **devuelve** (crédito = lo que pagó) |
| **EGRESO** | Lo que el kiosko **entrega** |
| **DIFERENCIA** | EGRESO − INGRESO → lo que cobras, o **Q 0.00** |

#### Reglas de precio (importante)

El sistema usa el **precio que el cliente pagó** en la venta original (con descuentos incluidos).

| Caso | Diferencia |
|------|------------|
| **Mismo producto** (fallo, defecto, mismo código) | **Q 0.00** |
| **Cincho — solo cambio de talla** | **Q 0.00** |
| **Producto diferente** (otro código / más caro) | Puede haber diferencia a cobrar |

> El valor de lo entregado **no puede ser menor** que el valor de lo devuelto (no hay reembolso de diferencia en este módulo).

#### Cambio CON diferencia (diferencia > Q 0.00)

1. **Cobrar y confirmar**.
2. Completa pago como en POS:
   - **Efectivo**, **Tarjeta** o **Mixto**.
   - Si es **tarjeta**: marca (**VISA / Mastercard / AMEX**), número de voucher, últimos 4 dígitos y monto del voucher.
3. Datos de factura (NIT / CF, nombre) — se **factura la diferencia** (FEL).
4. El inventario se mueve solo (entra lo devuelto, sale lo entregado).
5. Estado **Completado**. Puedes **Imprimir** la boleta desde la lista.

#### Cambio SIN diferencia (diferencia = Q 0.00)

1. **No se cobra ni se factura.**
2. Escribe el **motivo** (ej. cambio de talla, fallo de fábrica).
3. **Enviar solicitud de cambio**.
4. Queda **Pendiente autorización**.
5. **Logística** (o supervisora con permiso) en la pestaña **Autorizaciones pendientes** → **Autorizar** o **Rechazar**.
6. Al **autorizar**: se mueve el inventario y pasa a **Completado**.
7. Al **rechazar**: indiquen motivo; **no** se mueve stock.

---

### Reintegros pendientes

Solo aplica a **devoluciones aptas** que aún no se enviaron a bodega:

1. Pestaña **Reintegros pendientes**.
2. **Reintegrar** en la fila.
3. El producto **sale del kiosko** hacia **Bodega PT**.

---

### Autorizaciones pendientes (logística)

Pestaña visible para quien autoriza cambios sin cobro:

- Lista de cambios en **Pendiente autorización**.
- **Autorizar** → aplica inventario.
- **Rechazar** → pide motivo; no mueve inventario.

---

### Imprimir otra vez

En **Boletas de cambio** y **Devoluciones**: botón **Imprimir** para reimprimir el comprobante.

---

## Tu turno en pasos

```
1. Abrir caja              → Ventas → Caja
2. Vender / facturar       → Ventas → POS
3. Desembolsos             → Ventas → Reportes → detalle de la venta
4. Boletas banco           → Ventas → Reportes (monto NETO)
5. Cambios / devoluciones  → Devoluciones / Reintegros (si aplica)
6. Mi conteo               → Ventas → Inventario → Mi conteo (opcional)
7. Cerrar caja             → Ventas → Caja
```

---

## Preguntas frecuentes

**¿No me deja vender?**  
Caja cerrada. Ábrela en la pestaña Caja.

**¿Producto gris o “sin stock”?**  
Sin stock en ese color, talla o herraje (Nuevo/Viejo).

**¿Me sale “no hay suficiente inventario” pero veo stock?**  
Avisa a administración: el stock que muestra el POS debe permitir cobrar. Si el error persiste tras actualizar el sistema, reporta producto, color y kiosko.

**¿Qué NIT pongo?**  
`CF` en la mayoría de ventas.

**¿Puedo vender sin factura?**  
No. Toda venta se factura (CF o NIT).

**¿Falló la factura pero la venta quedó?**  
Usa **Certificar factura** en el aviso o en el detalle de la venta.

**¿Depósito pendiente?**  
Vendiste con efectivo y falta boleta del banco. Deposita el **neto** (menos desembolsos ligados a esa venta).

**¿Dónde registro un desembolso?**  
En **Reportes de ventas → detalle de la venta** (no en la pestaña Caja).

**¿Anulo ayer?**  
No, solo ventas del turno con caja abierta.

**¿Debo anotar desembolsos?**  
Sí, todo efectivo que saques ligado a una venta para que cuadre el depósito y el cierre.

**¿Cliente paga con dos tarjetas?**  
En cobro elige **Tarjeta**, activa **Dividir pago en dos tarjetas**, indica montos y datos de cada voucher. Es **una venta** con **dos vouchers** en el reporte.

**¿Qué es el número de voucher?**  
El número del comprobante de la terminal de tarjeta (no es el NIT ni el número de factura).

**¿Dónde hago un cambio o una devolución?**  
**Kioscos → Devoluciones / Reintegros**. No es una pestaña dentro de Ventas.

**¿Cambio de cincho solo de talla — cobro diferencia?**  
No. Diferencia **Q 0.00** → queda pendiente de **autorización de logística**.

**¿Mismo producto por fallo o defecto — cobro?**  
No. Diferencia **Q 0.00** → autorización de logística.

**¿Cambio con producto más caro?**  
Sí cobras la **diferencia**, con pago y **factura FEL** de esa diferencia.

**¿Quién autoriza cambios sin cobro?**  
Usuario de **logística** (pestaña **Autorizaciones pendientes**).

**¿Qué hago si el cliente solo devolvió y el producto está bueno?**  
**Devolución** marcada como **apta** → luego **Reintegrar** a Bodega PT en **Reintegros pendientes**.

**¿Modo piloto?**  
Kiosko en prueba: las ventas se ven aquí pero no suman en reportes corporativos.

---

## Glosario corto

| Término | Significado |
|---------|-------------|
| POS | Pantalla de venta / cobro |
| CF | Consumidor final |
| FEL | Factura electrónica (SAT) |
| Voucher | Comprobante de la terminal de tarjeta |
| Herraje Nuevo / Viejo | Variante de stock del mismo color |
| Boleta de depósito | Comprobante del banco (sobre monto **neto** de la venta) |
| Depósito neto | Efectivo de la venta − desembolsos ligados |
| Desembolso | Gasto de efectivo ligado a una venta del turno |
| Pago con dos tarjetas | Una venta cobrada con 2 tarjetas; 2 vouchers en reporte |
| Turno de caja | Desde abrir caja hasta cerrarla |
| Boleta física | Número del talonario impreso (cambio o devolución) |
| Boleta de cambio | Cliente devuelve y recibe otro producto |
| Devolución | Cliente solo regresa producto |
| Pendiente autorización | Cambio sin cobro esperando aprobación de logística |
| Reintegro | Enviar producto apto devuelto a Bodega PT |
| INGRESO / EGRESO | En cambio: lo que entra vs lo que sale del kiosko |

---

*Manual Ventas del Kiosko — Fossiles (incluye cambios y devoluciones). Para inventario detallado, kardex y conteos oficiales ver el manual completo del kiosko.*
