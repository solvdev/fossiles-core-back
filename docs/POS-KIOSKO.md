# POS Kiosko Fossiles — Guía local

## Requisitos

- Java 21
- Node.js 18+
- PostgreSQL (desarrollo) o perfil `test` con H2 para pruebas automatizadas

## Backend (`fossiles-core-back`)

1. Copiar configuración:
   ```bash
   cp src/main/resources/application.properties.example src/main/resources/application.properties
   ```
2. Ajustar `spring.datasource.*` y `jwt.secret`.
3. Ejecutar migración opcional (si no usa `ddl-auto=update`):
   ```sql
   -- scripts/migration-kiosk-pos-enhancements.sql
   ```
4. Arrancar:
   ```bash
   ./gradlew bootRun
   ```
   API en `http://localhost:8080/api`

### Pruebas automatizadas POS

```bash
./gradlew test --tests "com.fossiles.fossilescorebackend.application.service.KioskPosServiceTest"
```

**Resultado esperado (última ejecución):** `BUILD SUCCESSFUL` — 8 tests passed:

| Test | Verifica |
|------|----------|
| `createSale_reducesInventory` | Venta rebaja `product_inventory_location` |
| `createSale_rejectsInsufficientStock` | Rechazo con mensaje de stock |
| `discount_percent_and_fixed` | Descuentos % y monto fijo |
| `discount_combo_2x1` | Promoción COMBO 2x1 |
| `cash_change_calculation` | `amountReceived` y `changeAmount` |
| `report_matches_sales` | Resumen coherente + ticket promedio |
| `encargada_cannot_access_other_kiosk` | Acceso restringido por kiosko |
| `admin_can_create_promotion` | Solo admin crea promos |

## Frontend (`fossiles-core-front`)

1. `.env`:
   ```
   REACT_APP_API_URL=http://localhost:8080/api
   ```
2. Arrancar:
   ```bash
   npm install
   npm start
   ```
3. Ruta: `/admin/kiosk-sales` (permiso `KIOSCOS.VENTAS_KIOSKO.VER`)

## Flujo manual E2E

1. Login con usuario encargada o admin.
2. Pestaña **POS** → tocar productos (badge de cantidad).
3. **Cobrar Q {total}** → modal: efectivo, botones Exacto/+50/+100/+200/+500, cambio.
4. Confirmar → pantalla de éxito con número de venta e “inventario rebajado”.
5. Pestaña **Reportes** → ver venta y resumen (ticket promedio).
6. Admin: selector de kiosko y pestaña **Promociones** (PERCENT, FIXED, COMBO).

## API principal

| Método | Ruta | Uso |
|--------|------|-----|
| GET | `/kiosk-pos/context` | Catálogo + filtros `search`, `categoryId`, `colorName` |
| POST | `/kiosk-pos/sales` | Registrar venta (totales en servidor) |
| GET | `/kiosk-pos/sales/{id}` | Detalle venta |
| GET | `/kiosk-pos/reports/my-kiosk` | Resumen + `averageTicket` |
| GET | `/kiosk-pos/promotions` | Promos activas por `kioskLocationId` |
| GET | `/kiosk-pos/taxpayers/lookup?taxId=` | Consulta NIT en FEL (nombre para factura) |

Auth: header `Authorization: Bearer {token}` desde `/auth/login`.

### Consulta NIT (FEL)

En `application.properties`:

```properties
fel.receptor.url=https://consultareceptores.feel.com.gt/rest/action
fel.receptor.emisor-codigo=TU_EMISOR_CODIGO
fel.receptor.emisor-clave=TU_EMISOR_CLAVE
fel.receptor.enabled=true
```

O variables de entorno `FEL_EMISOR_CODIGO` y `FEL_EMISOR_CLAVE`.

En el POS, al **Cobrar**, se ingresa NIT o CF; **Consultar NIT** llena el nombre desde FEL (no se guarda cliente en catálogo local).

### Emisión DTE al confirmar venta (FEL)

Tras registrar la venta, el backend:

1. Construye XML **FACT** (precios con IVA incluido, desglose 12 %).
2. **Firma** vía INFILE (`signer-emisores.feel.com.gt`).
3. **Certifica** vía API v2 (`certificador.feel.com.gt/fel/certificacion/v2/dte/`).

Migración DB:

```sql
-- scripts/migration-kiosk-sale-fel.sql
```

#### Ambiente PRUEBAS (implementación INFILE)

Misma URL que producción; el certificador marca el emisor en **implementación**:

- Serie del DTE: `** PRUEBAS **` (sin validez fiscal).
- Alerta sandbox en la respuesta del certificador.
- Límite ~2000 llamadas/día en implementación.

Configuración recomendada mientras prueban:

```properties
fel.emission.enabled=true
fel.emission.test-mode=true
fel.emission.required=false
# Credenciales demo INFILE (manual) — reemplazar por las de su asesor con su NIT de pruebas
fel.emission.sign-key=6456d06325f89acb30fbb2e7e7bec3c9
fel.emission.sign-alias=DEMO_FEL
fel.emission.cert-usuario=DEMO_FEL
fel.emission.cert-llave=E5DC9FFBA5F3653E27DF2FC1DCAC824D
fel.emission.nit-emisor=123456789
fel.emission.nombre-emisor=PRUEBA, SOCIEDAD ANONIMA
fel.emission.direccion=DIAGONAL 29 00-22 17 CALZADA LA PAZ Guatemala, GUATEMALA
fel.emission.frases[0].tipo=1
fel.emission.frases[0].escenario=1
# Solo agregue más frases si su asesor FEL las indica (tipo 2 escenario 1 suele rechazarse con afiliación GEN).
```

Con `fel.emission.required=false`, si FEL falla la venta **sí se guarda** y queda `fel_status=FAILED` (útil en pruebas). En producción use `test-mode=false` y `required=true`.

**Ventas piloto vs producción (kiosko por kiosko):** en **Catálogos → Ubicaciones**, marque **Modo piloto POS** en cada kiosko en prueba. Esas ventas se guardan con `test_sale=true` y no suman en el dashboard de ventas ni en el reporte general admin. Para activar un kiosko en producción, desmarque esa casilla (sin reiniciar el servidor). Las ventas piloto anteriores conservan su marca; no hace falta borrarlas. Migraciones: `migration-location-pos-test-mode.sql` y `migration-kiosk-sale-test-flag.sql`.

#### Producción

```properties
fel.emission.enabled=true
fel.emission.test-mode=false
fel.emission.required=true
fel.emission.sign-key=TU_LLAVE_FIRMA
fel.emission.sign-alias=TU_ALIAS_FIRMA
fel.emission.cert-usuario=TU_USUARIO_CERT
fel.emission.cert-llave=TU_LLAVE_CERT
fel.emission.nit-emisor=TU_NIT
fel.emission.nombre-emisor=RAZON SOCIAL
fel.emission.nombre-comercial=NOMBRE COMERCIAL
fel.emission.direccion=DIRECCION ESTABLECIMIENTO
```

Variables de entorno: `FEL_SIGN_KEY`, `FEL_CERT_USUARIO`, `FEL_NIT_EMISOR`, etc.

La pantalla de éxito muestra UUID, serie y número cuando certifica OK; avisa si la serie es de pruebas.
