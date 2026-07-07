# Fossiles ERP — Pendientes y cronograma (Jul 2026)

**Referencia:** Propuesta ERP Fossiles (Nov 2025)  
**Inicio de desarrollo:** Noviembre 2025  
**Revisión:** Julio 2026  
**Enfoque:** Solo lo que falta, con entregables semanales para micronograma

---

## Contexto

Desde **noviembre 2025 a julio 2026** (~8 meses) ya se construyó y puso en uso la mayor parte del ERP. Este documento **no repite ese trabajo**: solo organiza lo pendiente y cuándo cerrarlo.

### Ya construido (no entra al cronograma)

| Área | Lo que ya está operativo |
|------|--------------------------|
| **General** | Usuarios, roles, permisos, departamentos, centros de costo, catálogos, impuestos, series, formatos PDF |
| **Compras** | Solicitudes, OC, recepción, gastos menores, reportes, asientos automáticos |
| **Inventario** | 4 inventarios (materiales, PT, kiosko, devoluciones parciales), kardex, ajustes, transferencias, alertas críticas, app móvil |
| **Ventas** | Online, distribución, cuentas por cobrar LF, promociones |
| **Producción** | OPs, BOM, mesas, cinchos, entrega materiales, bodega PT, reportes |
| **POS** | Sesiones, FEL, cierre caja, devoluciones, cambios, reportes |
| **Contabilidad** | FEL, asientos de compras, cuentas por cobrar, config de cuentas |
| **Móvil** | Kardex QR, entrega OP, bodega PT, CRUD órdenes de producción |

**Avance global estimado:** ~75–80% de la propuesta original.

---

## Lo que falta (resumen)

| # | Pendiente | Impacto |
|---|-----------|---------|
| 1 | Pantallas y reportes sin terminar (inventario, kioskos, ventas vendedor) | Operación diaria con huecos visibles |
| 2 | Correo y notificaciones en el menú | Comunicación automática con proveedores/clientes |
| 3 | Flujo de devoluciones más formal | Control de stock defectuoso/en revisión |
| 4 | Cuentas por pagar y pagos a proveedores | Cierre administrativo |
| 5 | Libros fiscales exportables | Cumplimiento para contador |
| 6 | Planilla (cálculo quincenal, IGSS, ISR) | Nómina manual hoy |
| 7 | Pruebas finales, carga de datos, capacitación, go-live | Salida formal |

**Duración estimada del remanente (desarrollo):** **8 semanas** (jul–ago 2026).  
**Migración kioskos CITEC → ERP:** **16–20 semanas en paralelo** (ago–dic 2026), priorizando área metropolitana y alrededores.  
**Salida operativa central (ola 1):** semana 4.  
**Cierre administrativo completo:** semana 8.  
**Todos los kioskos migrados:** ~diciembre 2026.

---

## Cronograma semanal — solo pendientes

*Fecha de inicio sugerida: semana del 7 de julio de 2026. Ajusta las fechas según tu calendario real.*

### Semana 1 · 7–13 jul — General + reportes de inventario

**Objetivo:** cerrar configuración base y la primera pantalla de reportes vacía.

| Entregable | Detalle |
|------------|---------|
| Config de correo en menú | Exponer pantalla existente, probar envío |
| Notificaciones básicas | Alertas de stock crítico y recepciones con diferencia |
| Validación NIT duplicado | Clientes y proveedores |
| Reportes de inventario (web) | Valoración, existencias por ubicación, movimientos — conectar al backend |
| Demo semanal | Revisión con Fossiles: reportes de inventario funcionando |

**Criterio de aceptación:** ninguna pantalla de reportes de inventario vacía; correo configurable desde el sistema.

---

### Semana 2 · 14–20 jul — Devoluciones + ventas por vendedor

**Objetivo:** cerrar inventario de devoluciones y el canal comercial pendiente.

| Entregable | Detalle |
|------------|---------|
| Flujo de devoluciones formal | Estados: recibido → en revisión → aprobado/rechazado → stock devolución o baja |
| Reporte desempeño kioskos | Conectar pantalla stub a datos reales de POS |
| Ventas por vendedor | Conectar pantalla al backend (hoy hardcodeada) |
| Límite de crédito por cliente | Validación básica en ventas a crédito |
| Demo semanal | Flujo devolución completo + ventas vendedor con datos reales |

**Criterio de aceptación:** encargada de kiosko y vendedor LF pueden operar sin Excel paralelo.

---

### Semana 3 · 21–27 jul — Compras + reportes comerciales

**Objetivo:** pulir compras y reportes de ventas.

| Entregable | Detalle |
|------------|---------|
| Alertas en recepción | Notificación cuando cantidad recibida ≠ pedida |
| Aprobación de solicitudes | Flujo simple por rol (sin configuración compleja por monto) |
| Reportes comerciales | Ventas por colección, temporada y canal (kiosko + online + vendedor) |
| POS — referencia operativa | Villa Lobos ya validado (kiosko nuevo); en S3 revisar checklist antes de migraciones CITEC |
| Demo semanal | Recepción con alerta + reporte comercial consolidado |

**Criterio de aceptación:** compras con visibilidad de diferencias; reporte de ventas unificado por canal.

---

### Semana 4 · 28 jul – 3 ago — Go-live operativo (ola 1)

**Objetivo:** Fossiles opera producción, inventario, ventas y POS al 100% en el sistema.

| Entregable | Detalle |
|------------|---------|
| Pruebas punta a punta operativas | Compra → recepción → OP → PT → distribución → venta → FEL |
| Corrección de bugs críticos | Solo bloqueantes de operación |
| Manuales rápidos | Bodega, producción, kiosko (1-pager por rol) |
| Inicio CxP (backend) | Modelo y API de cuentas por pagar ligadas a OC |
| **Hito ola 1** | **Go-live operativo** — planta y tiendas en ERP |

**Criterio de aceptación:** un día completo de operación real sin volver a procesos manuales en planta/tiendas.

---

### Semana 5 · 4–10 ago — Cuentas por pagar + planilla (inicio)

**Objetivo:** arrancar cierre administrativo en paralelo.

| Entregable | Detalle |
|------------|---------|
| Cuentas por pagar (pantalla) | Listado de deuda por proveedor desde OC/recepciones |
| Registro de pagos | Pago total o parcial a proveedor |
| Libro de compras (borrador) | Export Excel con facturas de compra y recepciones |
| Planilla — estructura | Pantalla, período quincenal, selección de empleados activos |
| Planilla — cálculo base | Salario quincenal desde expediente de empleado |
| Demo semanal | Registrar un pago a proveedor + preview de planilla |

**Criterio de aceptación:** administración ve deuda por proveedor y puede registrar un pago.

---

### Semana 6 · 11–17 ago — Planilla (IGSS/ISR) + libro de ventas

**Objetivo:** planilla calculable y libros fiscales básicos.

| Entregable | Detalle |
|------------|---------|
| IGSS | Deducción laboral estándar Guatemala |
| ISR | Retención básica según tabla simple acordada con contador |
| Bonos y deducciones manuales | Líneas extra editables por empleado en cada quincena |
| Export planilla | Excel para banco y resumen por empleado |
| Libro de ventas | Export desde facturas FEL certificadas |
| Demo semanal | Calcular planilla de prueba de 1 quincena |

**Criterio de aceptación:** planilla de prueba con números validados por Fossiles/contador.

---

### Semana 7 · 18–24 ago — Integración contable + asientos nómina

**Objetivo:** que administración cierre mes desde el sistema.

| Entregable | Detalle |
|------------|---------|
| Asiento contable de nómina | Generado al aprobar planilla |
| Asiento contable de pago proveedor | Al registrar pago en CxP |
| Notas de crédito básicas | En cuentas por cobrar (clientes LF) |
| Vista consolidada contable | Compras + ventas + nómina en un panel de consulta |
| Producción — ajustes menores | Solo si surgieron en pruebas (no QA formal) |
| Demo semanal | Cierre de mes simulado: compras + ventas + planilla |

**Criterio de aceptación:** contador recibe libros de compras/ventas + asientos exportables.

---

### Semana 8 · 25–31 ago — Cierre del proyecto

**Objetivo:** go-live administrativo y entrega formal.

| Entregable | Detalle |
|------------|---------|
| Carga / ajuste de saldos iniciales | Inventarios, CxC, CxP según necesidad |
| Capacitación administración | Planilla, CxP, libros fiscales, reportes |
| Capacitación gerencia | Dashboards y reportes ejecutivos |
| Pruebas de regresión | Checklist completo (ver abajo) |
| **Hito final** | **Go-live administrativo + entrega del proyecto** |
| Soporte post go-live | 2 semanas de acompañamiento |

**Criterio de aceptación:** checklist de cierre completo y firmado por Fossiles.

---

## Vista de hitos

```
Jul 2026                                                          Ago 2026
|--S1--|--S2--|--S3--|--S4--|--S5--|--S6--|--S7--|--S8--|
 Gen    Inv    Comp   GO-LIVE  CxP   Plan   Cont   CIERRE
+Rep    +Vend  +Rep   operativo +Pago +IGSS  +Asien
                  +prep migr.         +Libros +Libros
```

| Hito | Semana | Fecha aprox. |
|------|--------|--------------|
| Reportes y correo listos | 1 | 13 jul |
| Ventas vendedor + devoluciones | 2 | 20 jul |
| Compras pulidas + reportes ventas | 3 | 27 jul |
| **Go-live operativo** | 4 | 3 ago |
| CxP + planilla base | 5 | 10 ago |
| Planilla IGSS/ISR + libros | 6 | 17 ago |
| Cierre contable integrado | 7 | 24 ago |
| **Entrega final del proyecto** | 8 | 31 ago |

---

## Detalle por módulo — solo lo pendiente

### General — 🟡 pendiente ~1 semana (S1)

- Correo en menú y notificaciones
- Validación NIT duplicado
- ~~Usuarios, roles, catálogos, parámetros~~ ✅ hecho

### Compras — 🟡 pendiente ~1 semana (S3 + S5)

- Alertas de diferencia en recepción
- Aprobación simple por rol
- Cuentas por pagar y pagos (S5)
- ~~Solicitudes, OC, recepción, reportes, asientos~~ ✅ hecho
- *Postergado:* cotizaciones a múltiples proveedores, historial de precios proveedor

### Inventario — 🟡 pendiente ~1.5 semanas (S1–S2)

- Reportes web (valoración, movimientos, existencias)
- Flujo formal de devoluciones
- ~~4 inventarios, kardex, ajustes, móvil, conteos kiosko~~ ✅ hecho
- *Postergado:* auditorías cíclicas en materiales/PT, reporte rotación/muerto

### Ventas — 🟡 pendiente ~1.5 semanas (S2–S3)

- Canal vendedor conectado
- Reportes por colección/temporada/canal
- Límite de crédito básico
- ~~Online, distribución, CxC, promociones~~ ✅ hecho
- *Postergado:* presupuestos reutilizables, reportes tabla dinámica

### Producción — 🟡 pendiente ~0.5 semana (S7, solo bugs)

- Ajustes de bugs encontrados en pruebas
- ~~OPs, BOM, mesas, cinchos, entrega, bodega PT~~ ✅ hecho
- *Postergado:* QA formal, desperdicios, tareas desde móvil

### POS — 🟡 pendiente ~0.5 semana (S3–S4)

- Prueba piloto en tienda — Villa Lobos ya cubre esto; en migraciones CITEC solo ajustes puntuales
- ~~Todo el flujo POS + FEL~~ ✅ hecho

### Planilla — 🔴 pendiente ~2 semanas (S5–S6)

- Cálculo quincenal, IGSS, ISR, bonos/deducciones, export, asiento
- ~~Expediente básico de empleados~~ ✅ hecho
- *Postergado:* préstamos, documentos adjuntos, historial laboral

### Contabilidad — 🟡 pendiente ~2 semanas (S5–S7)

- CxP, pagos, libros fiscales, asientos nómina, notas crédito básicas
- ~~FEL, asientos compras, CxC, config cuentas~~ ✅ hecho
- *Postergado:* plan de cuentas, balance, estado de resultados

---

## Micronograma — plantilla semanal

Copia esta tabla cada semana y marca avance:

| Día | Tarea | Responsable | Estado |
|-----|-------|-------------|--------|
| Lun | | | ☐ |
| Mar | | | ☐ |
| Mié | | | ☐ |
| Jue | | | ☐ |
| Vie | Demo / revisión con Fossiles | | ☐ |

**Ritmo sugerido:**
- **Lun–Jue:** desarrollo del entregable de la semana
- **Vie:** demo de 30–45 min con Fossiles, ajustes y cierre de la semana
- **Bloqueos:** escalar el mismo día; no arrastrar a la siguiente semana

---

## Migración kioskos: CITEC → ERP

Plan para pasar cada punto de venta que **hoy opera en CITEC** al ERP, **en paralelo** al cronograma de desarrollo.

### Villa Lobos — referencia, no migración

**Interplaza Villa Lobos (#46)** fue la prueba inicial porque es un **kiosko nuevo**: nunca estuvo en CITEC y **ya opera en el ERP** (POS, FEL, recepción de distribución). Sirve como **modelo de referencia** para configurar y capacitar los demás; no entra al cronograma de corte CITEC.

| Tipo | Kiosko | Estado |
|------|--------|--------|
| Kiosko nuevo (piloto) | Villa Lobos | ✅ Ya en ERP |
| Migración CITEC → ERP | ~45 kioskos restantes | Pendiente |

Guía operativa del piloto: [KIOSKO-PILOTO-VILLALOBOS-PRUEBAS.md](./KIOSKO-PILOTO-VILLALOBOS-PRUEBAS.md).

### Panorama

| Concepto | Detalle |
|----------|---------|
| Kioskos en catálogo FEL | **~46 ubicaciones** (códigos 2–47 en sistema) |
| Ya en ERP (kiosko nuevo) | Interplaza Villa Lobos — operando |
| Pendientes de migrar desde CITEC | **~45 kioskos** |
| Ritmo zona cercana | **2–3 kioskos por semana** (visita misma zona en 1–2 días) |
| Ritmo interior / lejanos | **1–2 kioskos por semana** |
| Tiempo por kiosko (ciclo completo) | **3–5 días** de trabajo + 1 día de corte |
| Duración total estimada | **16–20 semanas** (ago → dic 2026) |

### Criterio de prioridad geográfica

1. **Área metropolitana** — Guatemala, Mixco, Villa Nueva, Petapa, Chinautla, zonas 1–17  
2. **Alrededores cercanos** — Chimaltenango, Antigua/Sacatepéquez, Amatitlán (si aplica), Escuintla  
3. **Occidente** — Xela, Coatepeque, Quiché, Mazatenango, Retalhuleu  
4. **Nororiente y norte** — Jalapa, Jutiapa, Chiquimula, Cobán  

*Lógica: zonas donde puedes llegar el mismo día, corregir en sitio y volver sin perder una semana entera.*

### Proceso estándar por kiosko (5 días)

Micronograma para cada **migración desde CITEC**. Los pasos de POS, FEL y recepción siguen el mismo esquema probado en Villa Lobos; la diferencia principal es el **día 2** (corte de stock desde CITEC).

| Día | Actividad | Responsable |
|-----|-----------|-------------|
| **1 — Preparación** | Verificar ubicación en catálogo (FEL, serie interna, encargada, usuario `ENCARGADA_KIOSKO`) — igual que Villa Lobos | Admin / TI |
| **2 — Inventario** | **Corte CITEC:** último saldo en CITEC → conteo físico → cargar saldo inicial en ERP → envío de distribución si falta mercadería | Admin + bodega |
| **3 — Capacitación** | 1–2 h con encargada: login, recibir distribución, vender, FEL, cierre de caja | Supervisora + TI |
| **4 — Prueba real** | Mínimo 3 ventas (CF, NIT, tarjeta/efectivo) + 1 recepción de envío | Encargada (supervisión) |
| **5 — Corte CITEC** | Último cierre en CITEC → solo ERP desde el día siguiente → verificar stock y FEL | Admin |

**Criterio de corte:** el kiosko queda **100% en ERP** cuando pasó el checklist de go-live (abajo) y la encargada vendió al menos 1 día completo sin volver a CITEC.

*Kiosko nuevo (como fue Villa Lobos): omitir días 2 y 5 de CITEC; inventario entra por distribución desde cero.*

### Checklist go-live por kiosko

| # | Verificación | ☐ |
|---|--------------|---|
| 1 | Ubicación con categoría KIOSKO, código FEL y serie interna correctos | |
| 2 | Encargada asignada (1 usuario = 1 kiosko) | |
| 3 | Inventario inicial cargado y cuadra con conteo físico | |
| 4 | Envío de distribución recibido en pestaña POS | |
| 5 | Venta POS descuenta stock correctamente | |
| 6 | FEL NIT certifica con establecimiento correcto | |
| 7 | Cierre de caja del turno cuadra | |
| 8 | CITEC desactivado para ese punto (solo consulta histórica) | |

---

### Cronograma de migración por semanas

*Inicio sugerido: semana del 4 ago 2026 (después del go-live operativo central). Ajustar fechas según disponibilidad de visitas.*

#### Bloque A — Área metropolitana (semanas M1–M8 · ~23 kioskos desde CITEC)

| Semana | Fecha aprox. | Kioskos a migrar (2–3) | Zona / notas |
|--------|--------------|------------------------|--------------|
| **M1** | 4–10 ago | Santa Clara · El Frutal · Metrocentro | Villa Nueva — primera ola CITEC |
| **M2** | 11–17 ago | Miraflores II · Pacific Center · Miraflores | Villa Nueva / Z. 11 |
| **M3** | 18–24 ago | Naranjo · Eskala · Sankris | Mixco — 1 visita |
| **M4** | 25–31 ago | Zona 4 · Rus · Peri Roosevelt | Capital sur / Z. 7 |
| **M5** | 1–7 sep | Atanasio · Pradera Vistares · Majadas Once | Z. 12 / Z. 11 |
| **M6** | 8–14 sep | Tikal Futura · Cemaco · Portales | Z. 11 / Z. 10 / Z. 17 |
| **M7** | 15–21 sep | Metronorte · Santa Amelia · Pradera Concepción | Z. 17 / Z. 16 / Pinula |
| **M8** | 22–28 sep | Entrecueros · Vile Punto Roosevelt | Capital — cierre bloque A |

**Hito Bloque A:** ~23 kioskos capital + periferia migrados desde CITEC (+ Villa Lobos ya operando = ~24 en ERP).

---

#### Bloque B — Alrededores cercanos (semanas M9–M12 · ~8 kioskos)

| Semana | Fecha aprox. | Kioskos a migrar (2–3) | Zona / notas |
|--------|--------------|------------------------|--------------|
| **M9** | 29 sep – 5 oct | Chimaltenango (Pradera) · Andaria | Chimaltenango — 1 viaje |
| **M10** | 6–12 oct | Plaza Telares · San Lucas · Vile Studio | Antigua / Sacatepéquez |
| **M11** | 13–19 oct | Interplaza Escuintla · Pradera Escuintla · Santalú | Escuintla — 1 viaje |
| **M12** | 20–26 oct | Vile Studio Inara · Decobonsai · *Amatitlán si aplica* | Capital / sur cercano |

**Hito Bloque B:** área metropolitana + Chimaltenango, Antigua, Escuintla y alrededores cubiertos.

---

#### Bloque C — Occidente (semanas M13–M17 · ~10 kioskos)

| Semana | Fecha aprox. | Kioskos a migrar (1–2) | Zona / notas |
|--------|--------------|------------------------|--------------|
| **M13** | 27 oct – 2 nov | Interplaza Xela · Pradera Xela | Quetzaltenango |
| **M14** | 3–9 nov | Utzuleu · Coatepeque | Xela / occidente |
| **M15** | 10–16 nov | Los Celajes (Quiché) | Viaje largo — 1 kiosko |
| **M16** | 17–23 nov | Mazatenango · Retalhuleu | Sur occidente |
| **M17** | 24–30 nov | *Buffer / rezagados occidente* | Repaso o kioskos que fallaron corte |

**Hito Bloque C:** occidente operando en ERP.

---

#### Bloque D — Nororiente y norte (semanas M18–M20 · ~6 kioskos)

| Semana | Fecha aprox. | Kioskos a migrar (1–2) | Zona / notas |
|--------|--------------|------------------------|--------------|
| **M18** | 1–7 dic | Jalapa · Jutiapa | Oriente |
| **M19** | 8–14 dic | Chiquimula · Cobán (Plaza Magdalena) | Nororiente |
| **M20** | 15–21 dic | Cobán (Plaza del Parque) · cierre final | Alta Verapaz + auditoría |

**Hito final:** los ~45 kioskos en CITEC migrados; **~46 en ERP** contando Villa Lobos; CITEC solo histórico.

---

### Vista de hitos — migración

```
Ago 2026                              Sep              Oct              Nov              Dic
|--M1--M2--M3--M4--M5--M6--M7--M8--|--M9-M10-M11-M12--|--M13-M14-M15-M16-M17--|--M18-M19-M20--|
 Villa Nueva    Mixco    Capital      Chimal    Antigua   Escuintla    Xela occidente    Oriente
 3/sem          3/sem    3/sem        2-3/sem   2-3/sem   2-3/sem      1-2/sem           1-2/sem
```

| Hito | Semana | Fecha aprox. | En ERP (acumulado) |
|------|--------|--------------|-------------------|
| Villa Lobos operando (kiosko nuevo) | — | Ya activo | 1 |
| Primera ola migración CITEC | M1 | 10 ago | 4 |
| Capital sur y Mixco | M3 | 24 ago | 10 |
| Capital completa (bloque A) | M8 | 28 sep | ~24 |
| **Zona cercana completa** | M12 | 26 oct | ~33 |
| Occidente | M17 | 30 nov | ~43 |
| **Migración CITEC completa** | M20 | 21 dic | ~46 |

---

### Cómo encaja con el cronograma de desarrollo

| Semana dev | Semana migración | Qué pasa en paralelo |
|------------|------------------|----------------------|
| S1–S3 (jul) | — | Desarrollo; Villa Lobos ya operando en ERP |
| S4 (go-live operativo) | M1 inicia | Primera migración CITEC (Villa Nueva) |
| S5–S6 | M2–M5 | Desarrollo CxP/planilla + 2–3 kioskos/semana |
| S7–S8 (cierre proyecto) | M6–M9 | Cierre admin + Chimaltenango/Antigua/Escuintla |
| Post S8 | M10–M20 | Soporte + migración interior sin frenar operación |

---

### Plantilla de seguimiento por kiosko

Copia una fila por cada ubicación en tu micronograma:

| Kiosko | Serie | Est. FEL | Encargada | Prep ☐ | Stock ☐ | Capac. ☐ | Prueba ☐ | Corte CITEC ☐ | Fecha corte |
|--------|-------|----------|-----------|--------|---------|----------|----------|---------------|-------------|
| | | | | | | | | | |

**Catálogo de referencia** (orden sugerido de migración — validar nombres en **Catálogos → Ubicaciones**):

<details>
<summary>Lista completa ~46 kioskos por bloque</summary>

**Bloque A — Metropolitana (migración CITEC):** Santa Clara, El Frutal, Metrocentro, Miraflores II, Pacific Center, Naranjo, Eskala, Sankris, Zona 4, Rus, Peri Roosevelt, Atanasio, Pradera Vistares, Majadas Once, Miraflores, Tikal Futura, Cemaco, Portales, Metronorte, Santa Amelia, Pradera Concepción, Entrecueros, Vile Punto Roosevelt

**Ya en ERP (no migrar):** Villa Lobos — kiosko nuevo, referencia operativa

**Bloque B — Cercanos:** Chimaltenango (Pradera), Andaria, Plaza Telares, San Lucas, Vile Studio, Interplaza Escuintla, Pradera Escuintla, Santalú, Vile Studio Inara, Decobonsai

**Bloque C — Occidente:** Interplaza Xela, Pradera Xela, Utzuleu, Coatepeque, Los Celajes (Quiché), Mazatenango, Retalhuleu

**Bloque D — Nororiente:** Jalapa, Jutiapa, Chiquimula, Cobán (Magdalena), Cobán (Plaza del Parque)

</details>

---

### Riesgos y reglas durante la migración

| Riesgo | Mitigación |
|--------|------------|
| Stock no cuadra al cortar | Conteo físico el día 2; no cortar CITEC hasta cuadrar |
| FEL falla en tienda | Probar NIT antes del corte; tener modo piloto desactivado solo cuando FEL OK |
| Encargada sin capacitación | No cortar CITEC sin día 3–4 completos |
| Muchos kioskos la misma semana | Máximo 3 en zona cercana; si hay incidente, pasar 1 a la semana siguiente |
| CITEC y ERP en paralelo mucho tiempo | Máximo 2 días de operación dual; luego corte obligatorio |

---

## Fuera de este cronograma (fase posterior)

No bloquean go-live ni entrega del proyecto:

- Cotizaciones formales a múltiples proveedores
- Aprobaciones configurables por monto/departamento
- Presupuestos reutilizables de venta
- QA formal en producción
- Plan de cuentas y estados financieros completos
- Reportes tipo tabla dinámica
- Panel admin en app móvil
- Préstamos y expediente documental en planilla

---

## Checklist de cierre del proyecto

### Operación (ola 1 — semana 4)
- [ ] Flujo completo: compra → recepción → producción → venta → FEL → cobro
- [ ] Sin pantallas vacías en menú principal
- [ ] POS validado (referencia: Villa Lobos ya operando como kiosko nuevo)
- [ ] Manuales rápidos: bodega, producción, kiosko

### Migración kioskos (paralelo ago–dic)
- [ ] Bloque A completo — área metropolitana (~semana M8)
- [ ] Bloque B completo — Chimaltenango, Antigua, Escuintla (~semana M12)
- [ ] Bloque C completo — occidente (~semana M17)
- [ ] Bloque D completo — nororiente (~semana M20)
- [ ] CITEC fuera de operación en todos los puntos de venta

---
### Administración (ola 2 — semana 8)

- [ ] Cuentas por pagar con al menos 1 pago registrado
- [ ] Planilla de 1 quincena calculada y validada
- [ ] Libro de compras y ventas exportable
- [ ] Asientos de nómina y pagos generados
- [ ] Capacitación administración y gerencia realizada
- [ ] Ambiente productivo configurado y respaldado
- [ ] 2 semanas de soporte post go-live iniciadas

---

*Documento para planificación interna. Refleja solo el trabajo pendiente a partir de julio 2026. El desarrollo central son 8 semanas; la migración de kioskos corre en paralelo (~20 semanas). Ritmo: 2–3 kioskos/semana en zona cercana, 1–2 en interior. Reunión de avance semanal con Fossiles.*
