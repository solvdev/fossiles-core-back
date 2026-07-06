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
| **Caja** | Abrir/cerrar turno, gastos, cuadre de efectivo |
| **Inventario** | Ver stock (solo consulta) |
| **Recibir distribución** | Confirmar envíos del almacén |
| **Reportes** | Listado de ventas, depósitos, anulaciones |
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
| Gastos | Dinero que salió de caja ese día |
| Dif. caja | Sobrante o faltante al cerrar (si ya cerró) |
| Tarjeta | Ventas con tarjeta |
| Depósitos pendientes | Efectivo sin boleta de banco registrada |

---

## POS — Vender

### 1. Caja abierta

Si el POS está bloqueado → **Caja → Abrir caja — Q300**.

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

- Promociones vigentes se aplican **solas**.
- Botones **10% / 15% / 20%** para casos excepcionales.
- Empaques **nunca** tienen descuento.

#### Pago

| Forma | Datos |
|-------|-------|
| **Efectivo** | Cuánto te dio → ves el **cambio** |
| **Tarjeta** | Autorización + últimos 4 dígitos |
| **Mixto** | Monto efectivo + monto tarjeta |

**Confirmar** → pantalla de éxito con número de venta.

### Consultar otro kiosko

Abajo del POS: busca producto → **Consultar** → stock en otros locales.

---

## Caja

### Abrir

**Abrir caja — Q300** = fondo inicial en el cajón.

### Cuadre durante el turno

```
Efectivo esperado = Q300 + ventas en efectivo − gastos registrados
```

En pantalla: fondo, efectivo ventas, **gastos**, **esperado en caja**.

### Registrar gastos

Si sacaste efectivo del cajón (compras menores, etc.):

1. **Monto** + **Descripción**.
2. **Agregar gasto**.

Si no registras gastos, al cerrar parecerá que **falta dinero**.

### Cerrar

1. Registra **boletas de depósito** en Reportes (ventas en efectivo).
2. **Cerrar caja**.
3. Cuenta el efectivo **físico**.
4. Revisa la **diferencia** (sobra / falta).
5. Notas opcionales → **Confirmar cierre**.

No vendes más hasta abrir caja otra vez.

---

## Inventario (pestaña en Ventas)

Solo **consulta** — no modifica stock.

- Busca producto, color, talla.
- Filtros: todos / stock bajo / sin stock.

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

### Ver ventas

- Fechas **Inicio / Fin** → **Aplicar filtro**.
- Filtro depósito: **Todas** o **Pendientes**.
- Toca una fila → **detalle**.

### Cuadre con caja

Con caja abierta aparece un recuadro que compara efectivo del turno, gastos y esperado en caja.

### Boleta de depósito

1. Ventas en efectivo → depositar en banco.
2. En reportes, venta con **Pendiente** → abrir → **Registrar boleta** (número del banco).

### Anular venta

Solo si **caja abierta** y venta del **mismo turno**:
Detalle → **Anular venta** → motivo. El stock **vuelve** al kiosko.

### Excel / PDF

Exporta el listado del filtro actual.

---

## Promociones (solo admin)

Crear campañas: % , % por línea, monto fijo, combo.  
La cajera **no crea** promos aquí; las **aplica al cobrar** en POS.

---

## Tu turno en 5 pasos

```
1. Abrir caja      → pestaña Caja
2. Vender          → pestaña POS
3. Gastos de caja  → pestaña Caja (si gastaste efectivo)
4. Boletas banco   → pestaña Reportes
5. Cerrar caja     → pestaña Caja
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
Vendiste en efectivo y falta registrar la boleta del banco.

**¿Anulo ayer?**  
No, solo ventas del turno con caja abierta.

**¿Debo anotar gastos?**  
Sí, todo efectivo que saques del cajón para que cuadre al cerrar.

---

## Glosario corto

| Término | Significado |
|---------|-------------|
| POS | Pantalla de venta / cobro |
| CF | Consumidor final |
| FEL | Factura electrónica |
| Boleta de depósito | Comprobante del banco al depositar efectivo |
| Turno de caja | Desde abrir caja hasta cerrarla |

---

*Manual Ventas del Kiosko — Fossiles. Para inventario, cambios y devoluciones ver el manual completo del kiosko.*
