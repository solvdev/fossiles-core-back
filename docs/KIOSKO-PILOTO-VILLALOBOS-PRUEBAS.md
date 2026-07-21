# Piloto POS — CUEROGLAM Interplaza Villa Lobos (#46)

Guía paso a paso para poner en marcha y probar el piloto como **encargada**, con tareas de **administración** y **supervisora** al inicio.

Documentos relacionados: [POS-KIOSKO.md](./POS-KIOSKO.md), [TAX-INVOICE-PRUEBAS.md](./TAX-INVOICE-PRUEBAS.md).

---

## 1. Checklist previo (administrador)

Ejecutar **antes** de entregar credenciales a la encargada.

### 1.1 Migraciones SQL (PostgreSQL)

En orden:

```bash
psql -f scripts/migration-location-fel-fields.sql
psql -f scripts/seed-pilot-villa-lobos.sql
psql -f scripts/seed-role-encargada-kiosko.sql
```

También deben estar aplicadas las migraciones FEL previas (`migration-tax-invoice-certified-xml.sql`, `migration-tax-invoice-attempt.sql`, `migration-kiosk-pos-enhancements.sql`).

### 1.2 Configuración FEL (backend)

Copiar `application.properties.example` → `application.properties` y verificar bloque CUEROGLAM:

- NIT emisor: `11700874K`
- Credenciales prueba INFILE (`120091461_DEMO`)
- Frases: tipo **1** esc **1** (ISR) y tipo **4** esc **1** (exento IVA)
- **No** usar frase tipo 2 (error SAT 2614 con afiliación GEN)

### 1.3 Ubicación kiosko

**Catálogos → Ubicaciones** — verificar o crear:

| Campo | Valor |
|-------|-------|
| Código | `INT_VLOBOS` |
| Nombre | CUEROGLAM INTERPLAZA VILLALOBOS |
| Categoría | **KIOSKO** |
| Municipio | Villa Nueva |
| Departamento | Guatemala |
| Zona | 6 |
| Código establecimiento FEL | **46** |
| Dirección FEL | Km 13.80 Carretera al Pacífico, Interplaza Villa Lobos, 2do nivel Kiosco 18 |

### 1.4 Rol y usuario encargada

1. Verificar rol **ENCARGADA_KIOSKO** (script SQL o **Usuarios → Roles**).
2. Permisos mínimos:
   - `KIOSCOS.VENTAS_KIOSKO.VER`
   - `KIOSCOS.VENTAS_KIOSKO.CREAR`
   - `DISTRIBUCION.CONFIRMACION_RECEPCION.VER`
   - `DISTRIBUCION.CONFIRMACION_RECEPCION.CREAR`
3. **Usuarios → Nuevo**: username, contraseña, rol `ENCARGADA_KIOSKO`.
4. **Ubicaciones → editar Villa Lobos → Encargado** = esa usuaria.

Regla piloto: **1 encargada = 1 kiosko** (no asignar la misma persona a dos ubicaciones).

### 1.5 Inventario inicial (migración desde sistema anterior)

Para kioskos que vienen de otro sistema con saldos reales al corte, use la pestaña **Inventario inicial** en **Inventarios → Inventario de kioskos** (`/admin/kiosk-inventory`). No confundir con **Conteo físico** (periódico) ni con **Mi conteo** del POS.

Orden operativo (supervisora con `KIOSCOS.INVENTARIO_KIOSKO.VER`):

1. **Generar inventario** — crea filas en `kiosco_stock` en cero para el kiosko seleccionado (`POST /api/kiosco-inventory/initialize?locationId={id}`).
2. Pestaña **Inventario inicial** → **Iniciar inventario inicial** — abre borrador DRAFT.
3. Buscar producto → color → (cinchos FOSS: tallas) → capturar cantidades reales del corte.
4. **Aplicar al stock** — crea movimientos **AJUSTE** con motivo fijo `Inventario inicial - migración` y deja el stock en esas cantidades. Solo puede aplicarse **una vez** por kiosko.
5. **Primer conteo físico** oficial — la columna **Ini.** del kardex debe coincidir con lo cargado (derivado de movimientos previos al periodo del conteo).

Migración SQL requerida antes del deploy: `scripts/migration-kiosco-opening-inventory.sql`.

API inventario inicial:

- `POST /api/kiosco-inventory/{locationId}/inventario-inicial`
- `PUT /api/kiosco-inventory/inventario-inicial/{id}/items`
- `POST /api/kiosco-inventory/inventario-inicial/{id}/aplicar`
- `GET /api/kiosco-inventory/{locationId}/inventario-inicial/estado`

Para kioskos nuevos sin corte histórico, basta con inicializar en cero y recibir mercadería por distribución (sin inventario inicial aplicado).

---

## 2. Login encargada

1. Abrir la app web e iniciar sesión con la usuaria piloto.
2. El menú debe mostrar **Kioscos → Ventas POS** (`/admin/kiosk-sales`).
3. **No** debe aparecer: inventarios, distribución completa, ajustes, contabilidad, promociones admin.

Si aparece el selector de kiosko, el usuario es admin o tiene permisos de más — revisar rol en BD.

---

## 3. Recibir mercadería (encargada — simple)

Cuando logística envía mercadería a tu kiosko, **tú confirmas la recepción** desde la misma pantalla del POS:

1. Ir a **Kioscos → Ventas POS**.
2. Pestaña **Recibir distribución**.
3. Solo aparecen envíos **SENT** cuyo destino es **tu kiosko** (misma ubicación asignada). Si no hay ninguno, la pestaña queda vacía.
4. Cuando llegue la mercadería, pulsa **Confirmar recepción**.
5. Vuelve a la pestaña **POS** — los productos ya aparecen para vender.

Si hay faltantes o daños, avisa a tu supervisora (ella puede usar la pantalla completa de **Distribución → Confirmación de recepción** con cantidades parciales).

**Supervisora / logística** (antes de que la encargada reciba):

1. Crear distribución con destino Villa Lobos.
2. Confirmar borrador → **Enviar** (estado SENT).
3. Opcional: verificar con SQL `scripts/audit-distribution-kiosk-receipt-inventory.sql` después de la recepción.

---

## 4. Vender en POS (encargada)

1. Ir a **Kioscos → Ventas POS**.
2. Debe abrirse **directamente** el POS de Villa Lobos (sin dropdown de kiosko).
3. Los productos recibidos aparecen en el catálogo con cantidad > 0.
4. Agregar productos al carrito → **Cobrar**.
5. **Factura FEL:**
   - **NIT**: ingresar NIT válido → el sistema consulta nombre en FEL → marcar emitir factura → confirmar venta.
   - **Consumidor final (CF)**: sin marcar factura → no se emite DTE.
6. Pantalla de éxito: número de venta, total, y enlace para **descargar XML** certificado (si FEL OK).

---

## 5. Verificar inventario

1. Supervisora: **Inventarios → Por ubicación** → Villa Lobos.
2. La cantidad del producto vendido debe haber **bajado exactamente** por la venta POS.
3. La encargada **no** puede hacer ajustes ni transferencias manuales al kiosko.

---

## 6. Qué NO puede hacer la encargada

| Acción | Resultado esperado |
|--------|-------------------|
| Ajuste de inventario | Sin menú / API 403 |
| Transferencia manual a kiosko | Sin permiso / API 403 |
| Ver stock de otros kioskos | Solo su POS |
| Crear promociones | Solo admin |
| Confirmar recepción de otro kiosko | Solo envíos de su kiosko asignado |
| Forzar otro `kioskLocationId` en API | “No tienes acceso al kiosko seleccionado” |

---

## 7. Limpieza de encargadas anteriores

**No eliminar** usuarios con historial de ventas (error “datos relacionados”).

Procedimiento seguro:

1. **Ubicaciones** → reasignar **Encargado** a la encargada nueva (o dejar vacío).
2. **Usuarios** → **Desactivar** la encargada vieja (`inactive`). No borrar.
3. Al desactivar, el sistema **limpia automáticamente** `encargado_id` en ubicaciones donde figuraba esa usuaria.

---

## 8. Troubleshooting

### Sin stock en POS

- ¿Se confirmó la **recepción** del envío de distribución?
- ¿El producto estaba en el envío?
- ¿Inventario inicializado para categoría KIOSKO en esa ubicación?

### FEL “Omitida” o “Error”

- Revisar `application.properties` (credenciales CUEROGLAM, `fel.emission.enabled=true`).
- Ubicación con **código establecimiento 46** (no el fallback global `1`).
- Frases FEL: tipo 1 y 4, **no** tipo 2.
- Bitácora: tabla `tax_invoice_attempt` o pantalla Contabilidad → Facturas.

### “Tu usuario no tiene kiosko asignado”

- En **Ubicaciones**, el campo **Encargado** debe apuntar a la usuaria activa.
- Usuario debe estar `active`, no `inactive`.
- Categoría de la ubicación debe ser **KIOSKO**.

### Encargada ve pantallas de más

- Revisar rol: solo `ENCARGADA_KIOSKO` con permisos POS.
- Cerrar sesión y volver a entrar.

---

## 9. Checklist go-live (fin de semana)

| # | Prueba | Responsable | Criterio |
|---|--------|-------------|----------|
| 1 | Aislamiento POS | Encargada | Sin dropdown; error si fuerza otro kiosko |
| 2 | Envío + recepción | Supervisora + Encargada | Supervisora envía; encargada confirma en pestaña Recibir distribución |
| 3 | Venta POS | Encargada | KIOSK_SALE; stock baja |
| 4 | FEL NIT | Encargada | CERTIFIED; XML descargable; establecimiento 46 |
| 5 | FEL CF sin checkbox | Encargada | Sin factura |
| 6 | Encargada intenta ajuste | Encargada | Sin acceso / 403 |
| 7 | Supervisora ajuste | Supervisora | Solo si necesario |
| 8 | Desactivar encargada vieja | Admin | Sin error; encargado desasignado |

---

## Scripts de referencia

| Archivo | Uso |
|---------|-----|
| `scripts/migration-location-fel-fields.sql` | Columnas FEL en `locations` |
| `scripts/seed-pilot-villa-lobos.sql` | Ubicación Villa Lobos #46 |
| `scripts/seed-role-encargada-kiosko.sql` | Rol y permisos encargada |
| `scripts/audit-distribution-kiosk-receipt-inventory.sql` | Auditoría recepción → stock |
