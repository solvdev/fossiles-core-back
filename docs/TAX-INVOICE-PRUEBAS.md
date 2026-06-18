# Guía de pruebas — Facturación electrónica (FEL)

Instrucciones para emitir facturas en cada punto del sistema.  
Ambiente actual: **pruebas INFILE** (implementación / sandbox).

---

## Antes de probar (una sola vez)

### 1. Base de datos

Ejecutar en PostgreSQL:

```sql
-- scripts/migration-kiosk-sale-fel.sql
-- scripts/migration-tax-invoice.sql
-- scripts/migration-tax-invoice-attempt.sql
```

### 2. Backend — `application.properties`

Copiar bloque de pruebas desde `application.properties.example` o `docs/POS-KIOSKO.md`:

```properties
fel.receptor.enabled=true
fel.receptor.emisor-codigo=TU_CODIGO_CONSULTA_NIT
fel.receptor.emisor-clave=TU_CLAVE_CONSULTA_NIT

fel.emission.enabled=true
fel.emission.test-mode=true
fel.emission.required=false
fel.emission.sign-key=6456d06325f89acb30fbb2e7e7bec3c9
fel.emission.sign-alias=DEMO_FEL
fel.emission.cert-usuario=DEMO_FEL
fel.emission.cert-llave=E5DC9FFBA5F3653E27DF2FC1DCAC824D
fel.emission.nit-emisor=123456789
fel.emission.nombre-emisor=PRUEBA, SOCIEDAD ANONIMA
fel.emission.nombre-comercial=PRUEBA
fel.emission.direccion=DIAGONAL 29 00-22 17 CALZADA LA PAZ Guatemala, GUATEMALA
fel.emission.frases[0].tipo=1
fel.emission.frases[0].escenario=1
# No usar frases[1] tipo=2 escenario=1 salvo indicación del asesor FEL (error 2614 con afiliación GEN).
```

| Propiedad | Pruebas | Producción |
|-----------|---------|------------|
| `fel.emission.enabled` | **`true`** (obligatorio para certificar; default del código es `false`) | `true` |
| `fel.emission.test-mode` | `true` | `false` |
| `fel.emission.required` | `false` (venta no se pierde si FEL falla) | `true` |
| Credenciales | Demo INFILE o las de su asesor con su NIT de pruebas | Credenciales reales del emisor |

Reiniciar el backend después de cambiar propiedades.

### 3. Frontend

```bash
cd fossiles-core-front
npm start
# REACT_APP_API_URL apuntando al backend
```

Permisos:

- POS: **Kioscos → Ventas POS**
- Online: **Ventas → Ventas Online**
- Contabilidad: **Contabilidad → Facturas FEL** (`CONTABILIDAD.FACTURAS.*`)

Sincronizar permisos desde el menú de administración si las rutas nuevas no aparecen.

---

## Resumen por lugar

| Lugar | Menú | ¿Disponible? | Cuándo emite |
|-------|------|--------------|--------------|
| **POS Kiosko** | Kioscos → Ventas POS | Sí | NIT automático; CF solo con «Emitir factura» |
| **Ventas Online** | Ventas → Ventas Online | Sí | Botón «Generar factura FEL» por fila |
| **Contabilidad** | Contabilidad → Facturas FEL | Sí | Factura manual + listado general |

---

## Estados FEL (`tax_invoice.status`)

Cada factura guardada en `tax_invoice` tiene un estado. En pantalla (Ventas Online, Contabilidad) se muestran badges; en base de datos el valor es el código en inglés.

| Estado (BD) | Badge / pantalla | Qué significa |
|-------------|------------------|---------------|
| *(sin registro)* | **Sin factura** (gris) | Aún no se generó factura para esa venta. En POS con **CF** sin marcar «Emitir factura» no se crea registro. |
| `DRAFT` | *(casi no visible)* | Borrador momentáneo al crear la factura, justo antes de intentar certificar. |
| `SKIPPED` | **Omitida** (amarillo) | El sistema **guardó** la factura en BD pero **no llamó a INFILE**. Ocurre cuando `fel.emission.enabled=false` (valor por defecto del backend). |
| `CERTIFIED` | **Certificada** (verde) | INFILE aceptó el DTE. Hay `fel_uuid`, serie y número. |
| `FAILED` | **Error** (rojo) | Se intentó certificar y falló (credenciales, XML, NIT emisor, etc.). Detalle en `fel_error`. |
| `VOID` | — | Reservado para anulación FEL (fase 2; aún no implementado). |

### Si le sale **Omitida** (`SKIPPED`)

Es la causa más frecuente en pruebas. El backend creó `tax_invoice`, pero la emisión FEL está **apagada**.

**Causa:** `fel.emission.enabled=false` o la propiedad no está definida en `application.properties`. El valor por defecto del código es `false`.

**Qué hacer:**

1. Agregar o corregir en `application.properties`:
   ```properties
   fel.emission.enabled=true
   fel.emission.test-mode=true
   fel.emission.required=false
   ```
   (y el resto del bloque de credenciales de la sección «Antes de probar»).
2. **Reiniciar el backend**.
3. **Reintentar** la factura omitida:
   - **Contabilidad → Facturas FEL →** abrir detalle → **Reintentar FEL**, o
   - **Ventas Online →** botón generar factura otra vez (reutiliza el registro existente).

**Consulta en BD:**

```sql
SELECT id, internal_number, status, fel_uuid, fel_error, source_type, source_id
FROM tax_invoice
ORDER BY id DESC
LIMIT 10;
```

| Lo que ve en BD | Interpretación |
|-----------------|----------------|
| `SKIPPED` y `fel_uuid` NULL | FEL desactivado (`enabled=false`). |
| `FAILED` y texto en `fel_error` | FEL activo, pero INFILE rechazó o hubo error de firma/config. |
| `CERTIFIED` y UUID poblado | Certificación correcta. |

### Estados por canal

| Canal | Sin factura | Omitida | Certificada |
|-------|-------------|---------|-------------|
| **POS** | CF sin checkbox «Emitir factura» | NIT o CF con FEL apagado | NIT (o CF con checkbox) + `enabled=true` + INFILE OK |
| **Ventas Online** | Nunca se pulsó «Generar factura FEL» | Se generó con FEL apagado | Se generó con FEL on + INFILE OK |
| **Contabilidad manual** | — | Se creó con FEL apagado | Se creó con FEL on + INFILE OK |

---

## 1. POS Kiosko

**Ruta:** `/admin/kiosk-sales`  
**Flujo:** carrito → **Cobrar** → datos de facturación → confirmar.

### Pasos

1. Agregar productos al carrito.
2. Clic en **Cobrar**.
3. **Datos de facturación:**
   - **CF:** dejar `CF` → nombre «CONSUMIDOR FINAL». Marcar **«Emitir factura electrónica (CF)»** solo si desea FEL.
   - **NIT:** escribir NIT → **Consultar NIT** → confirmar (emite automáticamente).
4. Elegir forma de pago y **Confirmar**.
5. Pantalla de éxito:
   - **OK:** bloque «Factura electrónica (FEL)» con UUID, serie, número (`sale.invoice`).
   - **Pruebas:** serie `** PRUEBAS **` y aviso «sin validez fiscal».
   - **Error:** mensaje `FEL: …` si falló (con `required=false` la venta igual quedó guardada).

### Qué validar

- [ ] Venta con **NIT** → `tax_invoice.status` = `CERTIFIED` y `kiosk_sale.invoice_id` enlazado.
- [ ] Venta **CF** sin checkbox → sin registro en `tax_invoice`.
- [ ] Venta **CF** con checkbox → factura en `tax_invoice`.
- [ ] Inventario rebajado aunque FEL falle (`required=false`).

### Consulta rápida en BD

```sql
SELECT ks.sale_number, ks.customer_tax_id, ks.invoice_id,
       ti.status, ti.fel_uuid, ti.fel_serie, ti.fel_numero, ti.fel_error
FROM kiosk_sale ks
LEFT JOIN tax_invoice ti ON ti.id = ks.invoice_id
ORDER BY ks.id DESC
LIMIT 10;
```

---

## 2. Ventas Online

**Ruta:** `/admin/online-sales`

### Pasos

1. Verificar que la venta tenga **NIT/CF** en columna «NIT».
2. Columna **FEL:** badge `Sin factura` | `Certificada` | `Omitida` | `Error`.
3. Clic en botón verde (icono papel) **Generar factura FEL** en la fila.
4. Confirmar el diálogo.
5. Modal con UUID, serie y número (o error FEL).

### API

```http
POST /api/tax-invoices/from-online-sale/{onlineSaleId}
Authorization: Bearer {token}
```

---

## 3. Contabilidad — facturas manuales

**Rutas:**

- Listado: `/admin/accounting/invoices`
- Nueva: `/admin/accounting/invoices/new`
- Detalle: `/admin/accounting/invoices/{id}`

### Pasos

1. **Contabilidad → Facturas FEL → Nueva factura manual**.
2. NIT/CF + **Consultar NIT** (mismo servicio que POS).
3. Agregar líneas (descripción, cantidad, precio IVA incluido).
4. **Crear y certificar**.
5. En detalle: **Reintentar FEL** si quedó en `FAILED` u `SKIPPED` (Omitida).
6. Revisar **Bitácora de intentos FEL**: cada emisión/reintento (exitoso o fallido) queda registrado con líneas, montos y error FEL.

### API

| Método | Uso |
|--------|-----|
| `GET /api/tax-invoices` | Listado general |
| `POST /api/tax-invoices/manual` | Crear y certificar manual |
| `POST /api/tax-invoices/{id}/retry` | Reintentar certificación |
| `GET /api/tax-invoices/{id}` | Detalle + líneas + bitácora |
| `GET /api/tax-invoices/{id}/attempts` | Solo bitácora de intentos |

---

## Errores frecuentes en pruebas

| Síntoma | Causa probable | Qué hacer |
|---------|----------------|-----------|
| Badge **Omitida** / `SKIPPED` | `fel.emission.enabled=false` (default) | Poner `enabled=true`, reiniciar backend, reintentar factura |
| Error **2614** — frase tipo 2 | `fel.emission.frases[1].tipo=2` no aplica a su NIT (GEN) | Dejar solo frase tipo 1 escenario 1; quitar frases extra de `application.properties` |
| Error **Gran Total** — suma detalles | Venta online con varios ítems: `total_amount` legacy ≠ suma líneas | Corregido en mapper; reintentar factura desde Online o Contabilidad |
| «Faltan credenciales FEL» | Propiedades vacías | Revisar `fel.emission.*` y reiniciar backend |
| «No se pudo consultar el NIT» | `fel.receptor.*` incorrecto | Validar código/clave consulta receptores |
| Certificación rechazada | XML / frases / NIT emisor | Leer `fel_error` en `tax_invoice` o en bitácora `tax_invoice_attempt` |
| Serie `** PRUEBAS **` | Normal en implementación | No usar como comprobante fiscal |
| Venta no se guarda | `fel.emission.required=true` y FEL falló | En pruebas usar `required=false` |

---

## Checklist de smoke test

1. [ ] Migraciones `migration-kiosk-sale-fel.sql`, `migration-tax-invoice.sql`, `migration-tax-invoice-attempt.sql` y `migration-tax-invoice-certified-xml.sql` aplicadas.
2. [ ] Backend arranca sin error de config FEL.
3. [ ] POS: venta NIT → factura certificada en `tax_invoice`.
4. [ ] POS: venta CF sin checkbox → sin factura.
5. [ ] Online: botón generar factura → badge actualizado.
6. [ ] Contabilidad: factura manual certificada o `FAILED` visible en listado.
7. [ ] Bitácora: cada intento FEL aparece en detalle de factura (incluye `SKIPPED`/`FAILED`).
8. [ ] Pantalla éxito POS muestra aviso de **pruebas** si aplica.

---

## Referencias

- Arquitectura: [TAX-INVOICE-FEL.md](./TAX-INVOICE-FEL.md)
- Config POS: [POS-KIOSKO.md](./POS-KIOSKO.md)
- Manuales INFILE: carpeta `FEL/`
