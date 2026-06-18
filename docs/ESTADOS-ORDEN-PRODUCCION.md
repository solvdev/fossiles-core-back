# Estados y avance de órdenes de producción (OP)

Guía operativa para evitar inconsistencias como **avance 100 %** con **estado Pendiente**.

---

## 1. Dos cosas distintas en la pantalla de órdenes

En **Producción → Órdenes de producción** hay columnas que **no miden lo mismo**:

| Columna | Qué significa | Fuente en sistema |
|--------|----------------|-------------------|
| **Total / Prod. / Pend.** y **barra %** | Cuántas piezas ya entraron a **Bodega PT** vs lo planificado | `warehouse_received_qty` por línea de la OP |
| **Proceso** (badge amarillo/azul/verde) | Etapa operativa calculada en pantalla | Combina `status` de la OP + pendiente en bodega |
| **Estado** (Pendiente / En progreso / Completada) | Campo **`production_order.status`** en base de datos | Tareas, consumo de materiales, cinchos manual, etc. |

### Por qué puede verse 100 % y aún “Pendiente”

Es posible y **no siempre es un error de captura**:

1. **Bodega PT recibió todas las piezas** (12/12 → 100 %), pero la OP sigue en `PENDING` porque **las tareas de producción no están todas en COMPLETED** o **nunca se generaron tareas**.
2. **Las tareas existen pero ninguna se marcó “En progreso” / “Completada”** en el centro de producción → el sistema deja la OP en `PENDING` aunque físicamente ya haya producto en bodega.
3. **Recepción en bodega no cambia el estado a Completada** por sí sola; solo actualiza cantidades recibidas (y `warehouse_receipt_closed_at` al cerrar recepción).
4. **Cinchos (OPC)** pueden tener otro flujo de estado manual en su vista dedicada, independiente del avance en bodega.

**Regla práctica:**  
- **Avance %** = “¿cuánto hay en bodega PT?”  
- **Estado** = “¿qué dice el flujo de producción/tareas?”

---

## 2. Estados oficiales de la OP (`production_order.status`)

| Estado | Significado | Cómo llega ahí |
|--------|-------------|----------------|
| **PENDING** | Orden creada; producción no iniciada formalmente | Creación de OP; tareas sin iniciar; tareas pendientes sin ninguna en progreso |
| **IN_PROGRESS** | Producción en curso | Consumo de materiales; alguna tarea en progreso; rechazos en bodega PT; cinchos pasados a “En progreso” |
| **COMPLETED** | Producción (tareas) terminada según sistema | **Todas** las tareas no canceladas en `COMPLETED` (sincronización automática); cinchos marcados Completada manualmente; despacho online completo (casos VENTA_EN_LINEA) |
| **CANCELLED** | Orden cancelada | Acción manual |
| **IN_QA** | Legacy / poco usado | Se muestra como “En progreso” en pantalla |

Campos relacionados **distintos** del estado:

- **`warehouse_receipt_closed_at`**: cierre operativo de recepción en bodega PT (ver `docs/BODEGA-PT.md`).
- **`materials_consumed`**: materiales ya descontados de inventario.

---

## 3. Etiqueta “Proceso” (segunda columna)

La pantalla traduce el estado a una etapa más clara:

| Proceso mostrado | Condición |
|------------------|-----------|
| Pendiente en Producción | `status = PENDING` |
| En Producción | `status = IN_PROGRESS` |
| Pendiente en Bodega PT | `status = COMPLETED` pero aún faltan piezas por recibir en bodega |
| Lista para Despacho | `status = COMPLETED` y todas las piezas recibidas en bodega |
| Cancelada | `status = CANCELLED` |

Si hay **100 % en bodega** pero **Estado = Pendiente**, el proceso seguirá diciendo **“Pendiente en Producción”** — coherente con el bug operativo, no con la bodega.

---

## 4. Quién hace qué (responsabilidades)

### 4.1 Planeación / administración de OP

- Crear la OP con cliente, fechas, cantidades y vendedor correctos.
- **Generar tareas** desde la OP o el centro de producción cuando corresponda (no todas las OP las generan solas).
- Verificar que existan tareas antes de exigir que el estado avance.

### 4.2 Materiales

- Marcar entrega de materiales / cuero / troquel en tareas cuando aplique.
- **Consumir materiales** de la OP (`POST .../consume-materials`) al autorizar inicio → pasa la OP a **IN_PROGRESS**.
- Ruta: materiales relacionados con la OP y app móvil de entregas.

### 4.3 Centro de producción (mesas / tareas)

- Ruta: **Producción → Tareas por mesa** (centro de producción).
- Por cada tarea de la OP:
  1. Asignar mesa y fecha si aplica.
  2. Pasar a **En progreso** al iniciar trabajo.
  3. Al terminar en mesa, la tarea pasa a **Pendiente bodega PT** (`AWAITING_WAREHOUSE`) si la OP usa piezas en bodega PT.
  4. La tarea pasa a **Completada** cuando **todas** sus piezas están recibidas en bodega PT (no al pulsar Completar en mesa).
- **`actualDurationMinutes`** = desde `startedAt` (inicio en mesa) hasta `completedAt` (última recepción en bodega PT).
- **Al completar la última tarea**, el sistema ejecuta `syncProductionOrderStatusFromTasks` y debe poner la OP en **COMPLETED**.

> **Importante:** Si las tareas quedan en Pendiente, la OP **no** pasará a Completada aunque bodega muestre 100 %.

### 4.4 Bodega producto terminado (PT)

- Ruta: **Producción → Bodega PT** (`/admin/warehouse-view`).
- Recibir pieza por pieza (o lote) → sube **Prod.** y el **%**.
- **Al marcar pieza RECEIVED**, el sistema puede cerrar tareas en `AWAITING_WAREHOUSE` y registrar el tiempo real de producción.
- Si hay rechazos → puede regresar la OP a **IN_PROGRESS** y crear tareas de reproceso.
- Cuando no queden piezas pendientes de recepción → **Cerrar recepción en bodega** (`warehouse_receipt_closed_at`).
- Despacho / envíos es un paso **posterior** (distribución, venta online, OPV, etc.).

### 4.5 Cinchos (OPC / OPCF / OPCM)

- Vista: **Órdenes de cinchos en producción** y **Tablero día cinchos**.
- Estados de OP cinchos gestionados: cambio **manual** vía API dedicada (`PUT /api/production-orders/{id}/status`) solo tipos `CINCHOS_FOSSILES` / `CINCHOS_MARCAS`.
- Al pasar a **IN_PROGRESS** desde Pendiente, el sistema valida materiales disponibles.
- Coordinar estado de OP con tablero de día y tareas de cincho.

### 4.6 Distribución / ventas / OPV Luis Felipe

- Preparar envíos, liberaciones parciales, documentos ENVP.
- No confundir “envío generado” con “OP completada en producción”.

---

## 5. Flujo recomendado por tipo de OP

### KIOSKO / MARCAS / OPV (tipo NORMAL u OPV)

```
Crear OP → Generar tareas → Consumir materiales (IN_PROGRESS)
    → Trabajo en mesa (IN_PROGRESS) → Pendiente bodega PT (AWAITING_WAREHOUSE)
        → Recibir piezas en Bodega PT → tarea COMPLETED + tiempo real
            → Cerrar recepción en bodega
                → Preparar envío / facturación / CxC según canal
```

### VENTA EN LÍNEA

Similar a KIOSKO en producción y bodega; el despacho por cliente puede marcar **COMPLETED** cuando todos los envíos online están despachados.

### CINCHOS

```
Crear OP → Validar materiales → Estado IN_PROGRESS (manual o consumo)
    → Producción + tablero día → Estado COMPLETED (manual en vista cinchos)
        → Bodega PT + envíos OPC si aplica
```

### DISTRIBUCIÓN

La OP suele nacer de una distribución completada; seguir flujo de tareas + bodega + envíos de distribución.

---

## 6. Checklist cuando una OP “se ve mal”

Use esta lista antes de asumir fallo del sistema:

- [ ] ¿Existen tareas para esta OP? (botón **Tareas** en la fila)
- [ ] ¿Todas las tareas están en **Completada** (no solo Pendiente)?
- [ ] ¿Se consumieron materiales al inicio?
- [ ] ¿El avance 100 % es solo bodega (`Prod.` = `Total`)?
- [ ] ¿Se cerró recepción en bodega (`warehouse_receipt_closed_at`)?
- [ ] ¿Es cincho y falta cambiar estado manual a Completada?
- [ ] ¿Hay tareas de reproceso abiertas por rechazos en bodega?

### Acciones correctivas habituales

| Situación | Acción |
|-----------|--------|
| 100 % bodega, tareas sin completar | Completar tareas en centro de producción **o** corregir tareas que no reflejan la realidad |
| Sin tareas | Generar tareas desde la OP / centro de producción |
| Producción terminada, 0 % bodega | Recibir piezas en **Bodega PT** |
| OP COMPLETED pero pendiente en bodega | Normal: falta recepción PT; completar recepción y cerrar |
| Cinchos atascados en Pendiente | Cambiar estado en vista cinchos tras validar materiales y producción real |

---

## 7. Sincronización automática (referencia técnica)

El backend actualiza `production_order.status` desde tareas en `TaskController.syncProductionOrderStatusFromTasks`:

- Todas las tareas (no canceladas) **COMPLETED** → OP **COMPLETED**
- Alguna tarea **IN_PROGRESS** o **AWAITING_WAREHOUSE** → OP **IN_PROGRESS**
- Caso contrario (incluye “todas pendientes”) → OP **PENDING**
- Si **no hay tareas**, no se actualiza el estado por este mecanismo

Otros disparadores:

- Consumo de materiales → **IN_PROGRESS**
- Rechazo en recepción bodega → **IN_PROGRESS** (si no estaba COMPLETED)
- Cambio manual de estado → solo cinchos gestionados (endpoint `/status`)
- Despacho online total → **COMPLETED** (venta en línea)

**La recepción completa en bodega NO escribe `COMPLETED` en la OP.**

---

## 8. Buenas prácticas del equipo

1. **No usar solo el % de bodega** para saber si una OP terminó en producción.
2. **Completar tareas el mismo día** que se termina el trabajo en mesa.
3. **No recibir en bodega** piezas que aún no pasaron control de producción si eso confunde el seguimiento (aunque el sistema lo permita).
4. **Cerrar recepción en bodega** cuando no queden piezas pendientes.
5. En **cinchos**, alinear estado de OP, tablero de día y tareas.
6. Revisar columna **Proceso** además de **Estado** para ubicar la etapa real.

---

## 9. Mejora conocida (UX / sistema)

Hoy **avance %** y **estado** pueden desincronizarse porque miden procesos diferentes. Opciones futuras de desarrollo (no implementadas en este documento):

- Auto-ajustar estado cuando bodega = 100 % **y** tareas = 100 %.
- Mostrar advertencia en listado cuando `pct >= 100` y `status = PENDING`.
- Unificar “Proceso” y “Estado” en una sola columna clara para el usuario.

Mientras tanto, seguir este manual evita la mayoría de casos reportados.

---

## 10. Rutas rápidas en el sistema

| Pantalla | Ruta |
|----------|------|
| Listado OP | `/admin/production-orders` |
| Centro de producción / tareas | `/admin/tasks-by-station` |
| Bodega PT | `/admin/warehouse-view` |
| Cinchos | `/admin/cinchos-production` |
| Tablero día cinchos | `/admin/cinchos-day-board` |
| Preparar envíos | `/admin/prepare-shipments` |

Documentación bodega PT: `docs/BODEGA-PT.md`.

---

*Última actualización: mayo 2026 — Fossiles Core.*
