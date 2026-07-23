# Manual — Ventas del Kiosko

**Para encargadas y cajeras**  
Pantalla: **Kioscos → Ventas del Kiosko**

> Manual completo del módulo kiosko (inventario, devoluciones, cambios): ver [`MANUAL-POS-KIOSKO-USUARIO.md`](./MANUAL-POS-KIOSKO-USUARIO.md)

---

## ¿Qué hace esta pantalla?

Aquí **vendes**, **abres y cierras caja**, **ves reportes**, **recibes mercadería** del almacén y **consultas stock** sin salir del POS.

**No incluye:** ajustes de inventario, traslados, boletas de cambio ni devoluciones formales (otros menús de Kioscos).

---

## Al entrar

Verás arriba:
- **Nombre del kiosko** (solo el tuyo; admin/supervisora puede elegir otro).
- Tu **nombre**.
- **Caja abierta** (verde) o **cerrada** (gris).
- Aviso amarillo si hay **depósitos pendientes** (falta boleta del banco).

### Pestañas

| Pestaña | Para qué |
|---------|----------|
| **Resumen** | Ventas del mes, comparativos, tabla por día |
| **POS** | Cobrar al cliente |
| **Caja** | Abrir/cerrar turno, desembolsos, cuadre de efectivo |
| **Cierres** | Historial de cierres pasados (PDF/Excel) |
| **Inventario** | Ver stock + **Mi conteo** (control interno) |
| **Recibir distribución** | Confirmar envíos del almacén |
| **Reportes** | Ventas, desembolsos, depósitos bancarios, vouchers |
| **Promociones** | Solo administrador — crear descuentos |

---

## Reglas básicas

1. **Sin caja abierta no vendes.** Abre caja al empezar el día.
2. **Cada venta baja el inventario** del kiosko sola.
3. **CF** = consumidor final (sin NIT). Es lo usual.
4. **Empaques SUM-** no llevan descuento.

---

## Resumen

Números grandes:
- **Hoy** — ventas de hoy vs mismo día año pasado.
- **Mes en curso** y **mes anterior**.

Tabla **Ventas por día**:

| Columna | Qué es |
|---------|--------|
| Efectivo | Cobrado en efectivo en POS |
| Gastos / Desembolsos | Dinero que salió de caja ese día |
| Dif. caja | Sobrante o faltante al cerrar (si ya cerró) |
| Tarjeta | Ventas con tarjeta |
| Depósitos pendientes | Efectivo sin boleta de banco registrada |

---

## POS — Vender

### 1. Caja abierta

Si el POS está bloqueado → **Caja → Abrir caja** (fondo inicial configurado por kiosko; suele ser Q300).

### 2. Buscar producto

- Escribe código, nombre o color.
- Filtros: Productos/Empaques, Categoría, Línea, Color.

### 3. Agregar al carrito

- Toca el **color** del producto.
- **Cinchos:** elige **talla** en la ventana.
- Gris = sin stock.

### 4. Cobrar

- Revisa subtotal, descuento y total.
- **Cobrar Q…** → ventana de cobro.

#### Cliente / factura

| Campo | Qué poner |
|-------|-----------|
| NIT / CF | `CF` casi siempre |
| Consultar NIT | Si el cliente trae NIT válido |
| Emitir factura (CF) | Solo si pide factura electrónica |

#### Descuentos

- Promociones vigentes se aplican **solas** (mínimo 10% sobre precio de catálogo).
- Botones **10% / 15% / 20%** para casos excepcionales.
- **Cobrar sin descuento (precio normal):** en la ventana de cobro, marca el checkbox si el cliente paga **precio de catálogo** sin descuento ni promoción.
- Empaques **nunca** tienen descuento.

#### Pago

| Forma | Datos |
|-------|-------|
| **Efectivo** | Cuánto te dio → ves el **cambio** |
| **Tarjeta** | Marca, autorización y últimos 4 dígitos |
| **Tarjeta (2 tarjetas)** | Activa **Dividir pago en dos tarjetas** → monto de cada una + datos de ambas |
| **Mixto** | Monto efectivo + monto tarjeta (una sola tarjeta) |

#### Pago con dos tarjetas (misma venta)

A veces el cliente paga **con dos tarjetas** (no es mixto efectivo + tarjeta). En **Tarjeta**:

1. Marca **Dividir pago en dos tarjetas**.
2. Indica **monto tarjeta 1** y **monto tarjeta 2** (deben sumar el total; al poner la primera, la segunda se calcula sola).
3. Completa **marca, autorización y últimos 4 dígitos** de **cada** tarjeta.
4. **Confirmar** → queda **una sola venta**, pero en reportes aparecen **dos vouchers**.

En el listado de ventas y en el detalle verás el detalle de **ambas** tarjetas (Tarjeta 1 y Tarjeta 2).

**Confirmar** → pantalla de éxito con número de venta.

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

En pantalla: fondo, efectivo ventas, **desembolsos**, **esperado en caja**.

### Registrar desembolsos

Si sacaste efectivo del cajón:

1. **Monto** + **Descripción**.
2. **Venta (opcional):**
   - **General de caja** — gasto del fondo o del turno (no ligado a una venta).
   - **Elegir venta** — si gastaste el efectivo **de esa venta**; se descuenta del **depósito neto** (ej. venta Q500 − gasto Q50 → depositar Q450).
3. **Agregar desembolso**.

También puedes agregar desembolsos ligados a una venta desde **Reportes → detalle de la venta**.

Si no registras desembolsos, al cerrar parecerá que **falta dinero**.

### Cerrar

1. Registra **boletas de depósito** en Reportes (ventas en efectivo).
2. **Cerrar caja**.
3. Cuenta el efectivo **físico**.
4. Revisa la **diferencia** (sobra / falta).
5. Notas opcionales → **Confirmar cierre**.

No vendes más hasta abrir caja otra vez.

---

## Inventario (pestaña en Ventas)

**Consulta** de stock — no modifica inventario.

- Busca producto, color, talla.
- Filtros: todos / stock bajo / sin stock.

**Mi conteo:** sub-pestaña para contar vitrinas solo como control personal (no reemplaza el conteo oficial de supervisora).

---

## Cierres

Historial de turnos **ya cerrados**. Ver reporte, PDF o Excel de cada cierre.

---

## Recibir distribución

Cuando llega mercadería del almacén:

1. Aparecen envíos **pendientes** para tu kiosko.
2. **Revisar y confirmar**.
3. Revisa cantidades; anota faltantes si hay.
4. **Confirmar recepción**.

Después ya puedes **vender** esos productos en POS.

---

## Reportes de ventas

Elige **tipo de reporte:** Ventas · Desembolsos · Depósitos bancarios · Voucher (tarjeta) · Hoja principal.

### Ver ventas

- Fechas **Inicio / Fin** → **Aplicar filtro** (atajos: Hoy, Ayer, etc.).
- Filtro depósito: **Todas** o **Pendientes**.
- Toca una fila → **detalle** (desembolsos, boleta, anular).

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

Exporta el reporte del tipo seleccionado.

### Reporte Voucher (tarjeta)

Lista las ventas con tarjeta para conciliar con el banco. Si una venta se cobró con **dos tarjetas**, aparecen **dos filas** (un voucher por tarjeta), ligadas a la **misma venta** y factura.

En el reporte **Ventas**, la columna de pago muestra el detalle de **ambas** tarjetas cuando aplica.

---

## Promociones (solo admin)

Crear campañas: % , % por línea, monto fijo, combo.  
La cajera **no crea** promos aquí; las **aplica al cobrar** en POS.

---

## Tu turno en 6 pasos

```
1. Abrir caja       → pestaña Caja
2. Vender           → pestaña POS
3. Desembolsos      → Caja o detalle de venta en Reportes
4. Boletas banco    → Reportes (monto NETO)
5. Mi conteo        → Inventario → Mi conteo (opcional)
6. Cerrar caja      → pestaña Caja
```

---

## Preguntas frecuentes

**¿No me deja vender?**  
Caja cerrada. Ábrela en la pestaña Caja.

**¿Producto gris?**  
Sin stock en ese color o talla.

**¿Qué NIT pongo?**  
`CF` en la mayoría de ventas.

**¿Depósito pendiente?**  
Vendiste con efectivo y falta boleta del banco. Deposita el **neto** (menos desembolsos ligados a esa venta).

**¿Desembolso general o de venta?**  
General = gasto del turno/fondo. Ligado a venta = reduce lo que depositas de esa venta.

**¿Anulo ayer?**  
No, solo ventas del turno con caja abierta.

**¿Debo anotar desembolsos?**  
Sí, todo efectivo que saques para que cuadre al cerrar.

**¿Cliente paga con dos tarjetas?**  
En cobro elige **Tarjeta**, activa **Dividir pago en dos tarjetas**, indica montos y datos de cada una. Es **una venta** con **dos vouchers** en el reporte.

---

## Glosario corto

| Término | Significado |
|---------|-------------|
| POS | Pantalla de venta / cobro |
| CF | Consumidor final |
| FEL | Factura electrónica |
| Boleta de depósito | Comprobante del banco (sobre monto **neto** de la venta) |
| Depósito neto | Efectivo de la venta − desembolsos ligados |
| Desembolso | Gasto de efectivo del turno |
| Pago con dos tarjetas | Una venta cobrada con 2 tarjetas; 2 vouchers en reporte |
| Turno de caja | Desde abrir caja hasta cerrarla |

---

*Manual Ventas del Kiosko — Fossiles. Para inventario, cambios y devoluciones ver el manual completo del kiosko.*
