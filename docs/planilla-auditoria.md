# Auditoría Fase 1 — Módulo de planilla (solo lectura)

Fecha: 2026-09-21  
Alcance: `fossiles-core-back`, `fossiles-core-front`, `fossiles-mobile-inventory`.  
Fuente de esquema adicional: dump `dbfoss/Fossiles_PRD-2026_08_19_14_17_48-dump.sql` (no se inventaron tablas).  
**No se modificó código de negocio.** Este archivo es el entregable de la Fase 1.

## Stack detectado

- **Backend:** Java 21, Spring Boot 3.5.8, Spring Security (JWT), Spring Data JPA / Hibernate, Lombok, validación Bean Validation. Base de datos **PostgreSQL** (AWS RDS `fossilesgt` / `fosstest`). `spring.jpa.hibernate.ddl-auto=update`; migraciones puntuales en `scripts/*.sql` (no hay Flyway/Liquibase). Pruebas: JUnit 5 + H2.
- **Frontend ERP:** React (CRA `react-scripts` 5), React Router 6, Reactstrap 8 / Bootstrap 4 (Paper Dashboard Pro), `xlsx`/`jspdf` para exportes. Permisos de menú por códigos (`ORGANIZACION.EMPLEADOS.*`, `CONTABILIDAD.FACTURAS.*`).
- **App móvil:** inventario/producción/logística; **no** hay módulo de planilla ni de empleados.
- **Dominios ya fuertes y reutilizables:** empleados (CRUD), usuarios/roles/permisos, departamentos/centros de costo/unidades operativas, kioscos (`locations` + POS `kiosk_sale` con `sold_by`), inventario kiosco y envíos internos, CxC de **clientes** (no de empleados), FEL (`tax_invoice`).
- **Contabilidad actual:** facturación FEL + asientos de compras (`accounting_entry` para OC/recepción) + kardex. **No** hay libro diario de planilla ni catálogo contable de sueldos.

## Hallazgo transversal (no confundir nombres)

En el código de hoy, **«planilla» no es nómina**. Significa tres cosas distintas:

1. **Compra de colaborador al 50 %** — `internal_shipment_request.request_type = 'PLANILLA'` (envío interno ENVI, aprobación Contabilidad, 50 % hardcodeado).
2. **Costo de manufactura** — `MANUFACTURING_PAYROLL_CINCHOS` / `MESAS` / `WAREHOUSE` y `product_category.payroll_total` (totales de salarios para costo/hora, no pago a personas).
3. **Plantilla de impresión** — `PrintFormatsForm.js` tipo documento `PAYROLL` (variables `quincenaBruta`, `igssDeduction`, etc.) **sin motor que las llene**.

El módulo de nómina de las secciones 2–9 **no existe** como corrida, período, conceptos ni prestaciones.

## Matriz de requerimientos

| # | Requerimiento | Estado | Archivos/tablas relacionadas | Qué falta / notas |
|---|---------------|--------|------------------------------|-------------------|
| 2.1 | Ficha de empleado (DPI, NIT, IGSS, banco, ingreso/baja, estado) | ⚠️ Parcial | `employee`; `EmployeeEntity.java`; `EmployeesForm.js`; `EmployeeService.java` | Hay nombre, DPI, email, teléfono, `hire_date`, `bank_account`, `position`, `salary`, `status`. **No hay NIT, número de afiliación IGSS, fecha de baja.** Estado solo `active`/`inactive` (no suspendido/retirado). DPI se reusa como `recipient_tax_id` en envíos planilla. |
| 2.2 | Grupo planta/kioscos/admin + kiosco con historial | ⚠️ Parcial | `department` (datos: `DEPT-PRODUCCION`, `DEPT-KIOSCOS`, `DEPT-ADMIN`); `operational_unit` (kioscos CUEROGLAM…); `locations.encargado_id` | Los 3 grupos **ya existen como departamentos**, no como enum `PLANTA_PRODUCCION`/`KIOSCOS`/`ADMINISTRACION`. Kiosco del empleado: `operational_unit_id` (sin historial). Encargado de kiosco es **usuario** (`locations.encargado_id`), no empleado, y no hay `employee_kiosk_assignments`. |
| 2.3 | Tipo de vinculación PLANILLA vs SERVICIOS | ❌ Falta | — | No hay columna ni enum. `PLANILLA` en envíos internos = compra al 50 %, no tipo laboral. |
| 2.4 | Historial de contratos y salarios con vigencia | ❌ Falta | `employee.salary` / `position` | El salario se **sobrescribe** en `EmployeeService.updateEmployee`. No hay `employee_contracts`. |
| 2.5 | Frecuencia QUINCENAL / MENSUAL | ⚠️ Parcial | `employee.quincena_bruta`, `quincena_neta` | Hay montos de quincena capturados a mano. **No hay** `pay_frequency` configurable por empleado/grupo. |
| 2.6 | Método de pago transferencia / efectivo / cheque | ⚠️ Parcial | `employee.payment_method`; `EmployeesForm.js` | UI: `TRANSFERENCIA BANCARIA` y `CHEQUE`. **No hay efectivo.** Texto libre en BD, no catálogo. |
| 2.7 | Vincular empleado ↔ usuario | ⚠️ Parcial | `employee_user`; `EmployeeEntity.users` | Existe M:N. El formulario asocia **un** usuario. Sirve para POS (`soldByUserId` es usuario, no empleado). No hay boleta self-service. |
| 3.1 | Catálogo de conceptos configurable | ❌ Falta | — | No hay `payroll_concepts`. |
| 3.2 | Salario ordinario proporcional al período | ❌ Falta | `employee.salary` | Monto mensual estático; no hay cálculo por días/ingreso/baja. |
| 3.3 | Bonificación incentivo (D. 37-2001) | ⚠️ Parcial | `employee.bonification` | Columna numérica en BD/entidad. **No está** en `EmployeeRequest`/`EmployeeResponse`/`EmployeesForm` (campo muerto). No hay vigencia ni proporcionalidad. |
| 3.4 | Otras bonificaciones por período | ⚠️ Parcial | `employee.total_bonus`, `meta_amount` | Columnas en BD; no expuestas en API/UI ni por período. |
| 3.5 | Horas extra diurnas/nocturnas con aprobación | ⚠️ Parcial | `employee.extra_hours`, `extra_hours_amount`, `night_hours` | Snapshot en ficha, no captura por período ni recargo 1.5x ni aprobación. Jornada de **producción** (`ProductionShift`, `TaskDeskHoursService`) es planificación de mesas, no HE de planilla. |
| 3.6 | Comisiones (sección 4) | ❌ Falta | ver 4.x | — |
| 3.7 | Ingresos/ajustes con motivo y usuario | ❌ Falta | `created_by`/`updated_by` en `employee` | Solo auditoría CRUD de ficha, no ajustes de nómina. |
| 3.8 | Días laborados / ausencias / suspensiones IGSS | ❌ Falta | — | No hay `attendance_records`. |
| 4.1 | Esquemas de comisión configurables | ❌ Falta | `kiosk_promotion` | Las «promociones» POS son **descuento al cliente** (`discount_type`, combos, tiers), no comisión al vendedor. |
| 4.2 | Asignación esquema × empleado/grupo/kiosco con vigencia | ❌ Falta | — | — |
| 4.3 | Cálculo desde ventas POS reales | ⚠️ Parcial (insumo sí, cálculo no) | `kiosk_sale`, `kiosk_sale.sold_by` → `KioskPosSaleResponse.soldByUserId`; anulación `voidSale` | Hay ventas atribuibles a **usuario** y kiosco. Falta mapear usuario→empleado y motor de comisión. |
| 4.4 | Devoluciones/anulaciones y crédito | ⚠️ Parcial (insumo) | `KioskPosService.voidSale`; CxC clientes `CustomerAccountService` | Anulaciones POS restan inventario. CxC es de **vendedores/clientes OPV**, no crédito de empleado. No hay regla de comisión al vender vs cobrar. |
| 4.5 | Preview de comisiones rastreable a la venta | ❌ Falta | — | — |
| 4.6 | Comisión afecta IGSS/ISR | ❌ Falta | — | — |
| 5.1 IGSS laboral | % parametrizable con vigencia | ⚠️ Parcial | `employee.igss_deduction` | Es **monto fijo** en la ficha, no % ni tabla con vigencia. Referencia 4.83 % no está en código. |
| 5.1 ISR relación de dependencia | Tablas/proyección anual | ❌ Falta | `tax` fila `ISR` 5.00 tipo ISR | Ese catálogo es impuesto de **productos/facturas** (junto a IVA-12), no retención de sueldos. |
| 5.1 Embargos | Orden/expediente y tope | ❌ Falta | — | — |
| 5.2.1 | Anticipos y préstamos con cuotas | ❌ Falta | `customer_account_entry.movement_concept_code` comentario «anticipo» | Anticipo ahí es CxC **cliente** legacy, no préstamo a empleado. |
| 5.2.2 | Descuento por compras de empleado | ❌ Falta (como descuento de nómina) | ver 6.x | La compra existe como envío; **no se descuenta del pago**. |
| 5.2.3 | Faltas, tardanzas, faltantes caja/inventario | ❌ Falta | Conteos físicos kiosco (`KioscoInventoryCountService`) | El conteo calcula diferencia de stock; no genera descuento a empleado. |
| 5.2.4 | Descuentos manuales + bitácora | ⚠️ Parcial | `employee.variable_deduction` | Columna en BD, no en API/UI. |
| 5.2.5 | Prelación y tope; neto no negativo | ❌ Falta | — | — |
| 6.1 | Solicitud/venta a empleado con precio de empleado | ⚠️ Parcial | `InternalShipmentRequestService`; `CreateStandaloneInternalShipmentModal.js`; `internal_shipment_request` | Flujo real: seleccionar empleado, productos, boleta BLS, tipo PLANILLA. No es venta POS. |
| 6.2 | % y base configurables; excepciones | ⚠️ Parcial | `ProductDistributionService.resolveInternalDiscount` **50 fijo** para PLANILLA; DEFECTOS sí permite % o Q | 50 % hardcodeado. Base = precio catálogo (no costo). Sin excepciones por categoría. |
| 6.3 | Salida de inventario + CxC al empleado | ⚠️ Parcial | ENVI / `product_shipment`; inventario PT | Autorizar genera envío e imprime documento. **No crea cuenta por cobrar al empleado.** CxC existente es de clientes. |
| 6.4 | Cobro: próxima planilla o N cuotas | ❌ Falta | — | — |
| 6.5 | Planilla toma CxC pendientes | ❌ Falta | — | No hay corrida de planilla. |
| 6.6 | Estados PENDIENTE → PARCIAL → PAGADA / ANULADA | ⚠️ Parcial | `internal_shipment_request.status`: `PENDIENTE` / `APROBADA` / `RECHAZADA` | Estados de **solicitud de envío**, no de cobro. Límite: 1 solicitud PLANILLA activa por empleado **por mes calendario** (`assertPlanillaMonthlyLimit`). |
| 6.7 | Aprobación antes de entregar | ✅ Existe | Contabilidad autoriza en `AuthorizeShipments.js`; luego se genera ENVI | Cubre aprobación operativa. No es flag «opcional configurable»; es el flujo. |
| 6.8 | Retiro: descontar de liquidación | ❌ Falta | — | No hay liquidación. |
| 6.9 | Límites monto / % salario | ⚠️ Parcial | 1 solicitud/mes hardcodeada | No hay tope en Q ni % del salario. |
| 6.10 | Comprobante interno y/o FEL | ⚠️ Parcial | Impresión ENVI interno (`enviInternalPrintHelper.js`, `standaloneInternalShipmentHelper.js`); talonario BLS | Documento interno sí. **No** se vio emisión FEL de venta a empleado. `recipient_tax_id` se llena con DPI. |
| 6.11 | Reporte compras empleado | ⚠️ Parcial | Listados de solicitudes/envíos internos; no reporte dedicado por período/producto/empleado | Filtrable a mano; no export de planilla de compras. |
| 6.12 | Devolución revierte inventario y saldo | ⚠️ Parcial | Devoluciones/envíos de distribución generales | No hay flujo «devolución compra empleado» ligado a saldo (no hay saldo). |
| 7.1 | Bono 14 | ❌ Falta | Nombre `BONO 14` aparece como **promoción POS** en `KioskPosServiceTest` | No es prestación laboral. |
| 7.2 | Aguinaldo | ❌ Falta | — | — |
| 7.3 | Vacaciones (acumulación/goce) | ❌ Falta | — | — |
| 7.4 | Indemnización / provisión | ❌ Falta | — | — |
| 7.5 | Liquidación / finiquito imprimible | ❌ Falta | `MonthlyLiquidationEntity` | Esa entidad es **comisión Forza/Guatex** de paquetería, no finiquito. |
| 7.6 | Aportes patronales IGSS/IRTRA/INTECAP | ❌ Falta | — | — |
| 7.7 | Provisiones a contabilidad | ❌ Falta | `accounting_entry` solo OC/recepción | No hay asiento de planilla. |
| 8.3.1 | Planilla por grupo y consolidada | ❌ Falta | — | — |
| 8.3.2 | Snapshot de parámetros en cada línea | ❌ Falta | — | — |
| 8.3.3 | Idempotencia al recalcular | ❌ Falta | — | — |
| 8.3.4 | Cierre de período inmutable | ❌ Falta | — | — |
| 8.3.5 | Bitácora de auditoría sensible | ⚠️ Parcial | `employee.created_by/updated_by`; `user_activity_log` (últimas acciones de usuario, no cambios de salario) | No hay audit log de conceptos/aprobaciones de nómina. |
| 8.3.6 | Permisos: ver salarios / calcular / aprobar / cerrar | ⚠️ Parcial | Front: `ORGANIZACION.EMPLEADOS.VER/CREAR/EDITAR/ELIMINAR`; `EmployeeController` **sin** `@PreAuthorize` (cualquier JWT autenticado pega al API) | Quien ve empleados **ve salario**. No hay permisos de planilla. |
| 8.3.7 | Decimales exactos / política de redondeo | ⚠️ Parcial | Empleado usa `BigDecimal`; costos manufactura en JS usan `parseFloat` | No hay política de redondeo de nómina. El 50 % de planilla-envío sí usa `BigDecimal`. |
| 8.3.8 | Pruebas de cálculos de planilla | ❌ Falta | No hay `*Payroll*Test` ni `*Employee*Test` | Pruebas existentes: POS, FEL, jornada de producción, etc. |
| 9.1 | Boleta PDF por empleado | ⚠️ Parcial | Variables `PAYROLL` en `PrintFormatsForm.js` | Plantilla genérica; **no hay generación** de boleta. |
| 9.2 | Resumen por grupo / kiosco / consolidado | ❌ Falta | — | — |
| 9.3 | Reporte IGSS y patronales | ❌ Falta | — | — |
| 9.4 | ISR retenido / constancias | ❌ Falta | — | — |
| 9.5 | Reporte pagos por servicios | ❌ Falta | — | — |
| 9.6 | Excel/CSV y archivo bancario | ⚠️ Parcial | Front ya exporta Excel/PDF en **otros** módulos (POS, CxC) | No hay layout bancario de nómina. Bancos vistos en POS depósitos: G&T Continental e Industrial (no implica archivo de planilla). |
| 9.7 | Saldos préstamos/anticipos/compras | ❌ Falta | — | — |
| 9.8 | Reporte comisiones con detalle de ventas | ❌ Falta | Reportes POS de ventas sí existen | Falta el cruce comisión. |
| 9.9 | Libro de salarios Mintrab | ❌ Falta | — | — |
| 9.10 | Asiento contable de planilla | ❌ Falta | `AccountingController` / `TaxInvoiceService` / `accounting_entry` | Módulo «contabilidad» = FEL + kardex + asientos de compra, no GL de sueldos. |

## Modelo de datos actual vs. propuesto

### Reutilizar (existen)

| Existe hoy | Uso actual | Encaje con el modelo propuesto |
|------------|------------|--------------------------------|
| `employee` | Ficha HR | Base de `employees`; hay que **extender**, no duplicar. |
| `employee_user` | Link a `users` | 2.7 |
| `department` | Kioscos / Administración / Producción | Candidato a **grupo** (hoy IDs 4/5/6 en el dump). Contradice el enum nuevo si se crea un campo paralelo sin migrar. |
| `operational_unit` | Sede, bodega, planta, cada kiosco CUEROGLAM | Candidato a sucursal; **no** está ligado a `locations.id`. |
| `locations` | Kioscos POS, FEL, encargado usuario | Kiosco real de ventas; hay que decidir si el empleado se asigna a `locations` o a `operational_unit`. |
| `users` + `role` + `permission` + `user_role` + `role_permission` | Auth y menú | 8.3.6 (faltan códigos PLANILLA.*) |
| `kiosk_sale` / items | Ventas POS, `sold_by` usuario, anulaciones | Insumo 4.3–4.4 |
| `internal_shipment_request` (+ lines, slip) | Compra colaborador 50 % / defectos | Semilla de 6.x; **no** es `employee_product_sales` ni CxC. |
| `product` / inventario kiosco y PT | Stock | 6.3 salida |
| `customer` / `customer_account_*` | CxC vendedores | **No reutilizar tal cual** para deuda de empleado (otro dueño, otro flujo). |
| `tax_invoice` | FEL kiosco/online | 6.10 si el contador pide factura. |
| `tax` | IVA/ISR de productos | No sirve como tablas ISR de sueldos. |
| `cost_center` | Asignable al empleado | Útil para asientos futuros (7.7 / 9.10). |
| `accounting_entry` | Compras | Patrón de asiento; hay que ampliar `document_type`. |
| `user_activity_log` | Actividad de sesión | Insuficiente para 8.3.5; se puede inspirar `audit_log`. |
| `system_config` | Costos manufactura «payroll» | **No** mezclar con parámetros legales de nómina. |

### No existen (hay que crear en Fase 2)

`employee_contracts`, `employee_kiosk_assignments`, `payroll_concepts`, `payroll_parameters`, `commission_schemes`, `employee_commission_assignments`, `payroll_periods`, `payroll_runs`, `payroll_run_lines`, `payroll_run_line_items`, `payroll_adjustments`, `employee_loans`, `employee_loan_installments`, `employee_product_sales`, `employee_receivables`, `attendance_records`, `benefit_accruals`, `service_payments`, `audit_log` de nómina.

Columnas en `employee` que el documento pide y **no están**: NIT, afiliación IGSS, fecha de baja, `group`, `employment_type`, `pay_frequency`, `kiosk_id` (FK a `locations`).

Columnas en `employee` que **sí están y no usa la API**: `bonification`, `extra_hours`, `extra_hours_amount`, `night_hours`, `meta_amount`, `total_bonus`, `variable_deduction`. Parecen restos de una planilla Excel/manual pegada a la ficha.

## Contradicciones documento vs. código

1. El documento asume un módulo de planilla; el ERP usa «planilla» para **compra interna al 50 %** y para **costo de fabricación**.
2. Propone `employees.group` enum; el ERP ya clasifica con `department_id` (mismos tres grupos).
3. Propone kiosco en el empleado; el POS asigna kiosco al **usuario encargado** (`locations.encargado_id`), y el empleado tiene `operational_unit_id` (catálogo paralelo a `locations`).
4. IGSS en ficha es **quetzales fijos**, no porcentaje con vigencia.
5. `tax.ISR` 5 % no es retención en relación de dependencia.
6. Estados de empleado `active`/`inactive` vs activo/suspendido/retirado.
7. Aprobación de compra empleado ya es obligatoria (Contabilidad), no «opcional configurable».
8. Límite de compras: 1 solicitud/mes, no tope monetario ni % de salario (P8).
9. 50 % está **en código** (`BigDecimal.valueOf(50)`), en contra del criterio «nada legal/comercial hardcodeado».
10. `MonthlyLiquidation` / `forza_commission` no es liquidación laboral.
11. No hay multiempresa en planilla; FEL usa un NIT emisor (`120091461` CUEROGLAM en el dump) con **nombres comerciales por kiosco**.

## Riesgos y deuda técnica encontrada

- **Seguridad de salarios:** el API `/api/employees` solo exige JWT; el permiso `ORGANIZACION.EMPLEADOS.VER` es de menú. Cualquier usuario autenticado que conozca el endpoint ve salarios y cuentas bancarias.
- **`ddl-auto=update`:** crear tablas de planilla así es frágil; hace falta migraciones SQL versionadas (el proyecto ya usa `scripts/migration-*.sql`).
- **Salario sin historial:** un update borra el sueldo anterior; rompe bono 14 / aguinaldo / promedio 12 meses si se calcula después.
- **Doble identidad empleado vs usuario:** comisiones y POS cuelgan de `users`; RR.HH. de `employee`. Sin disciplina en `employee_user` las comisiones no cierran.
- **Doble catálogo de kioscos:** `operational_unit` vs `locations` (códigos distintos). Asignar mal el grupo KIOSCOS vs el local POS.
- **Compra 50 % sin CxC:** inventario sale y **nadie cobra** en sistema. Si se engancha a planilla después, hay deuda histórica no registrada.
- **Límite 1/mes** puede contradecir cuotas (6.4) y excepciones (6.2).
- **Campos muertos** en `employee` (HE, bonos) invitan a seguir capturando «la quincena» a mano en la ficha en vez de un motor.
- **`parseFloat` en costos de manufactura** (ajeno a nómina, pero muestra que no hay estándar decimal en todo el ERP).
- **Riesgo legal** (servicios vs subordinación): el sistema hoy no puede ni marcar el tipo, menos auditarlo.
- **Pruebas:** cero cobertura de cálculos laborales.

## Plan de desarrollo propuesto (ordenado por prioridad y dependencias)

Alineado al §11 del documento, ajustado a lo que **ya hay**:

1. **Base HR (reutilizar `employee`)**  
   Extender ficha: NIT, IGSS afiliación, baja, estados, `employment_type`, `pay_frequency`, método de pago (incluir efectivo). Decidir mapeo **grupo = `department`** vs enum nuevo. Contratos con vigencia (dejar de sobrescribir `salary`). Historial de kiosco: elegir `locations` **o** `operational_unit` y no los dos. Permiso `EMPLEADOS.SALARIO.VER` en API. Pruebas de CRUD.

2. **Parámetros con vigencia + catálogo de conceptos**  
   Nueva tabla; no usar `tax` ni `system_config` de manufactura. Migrar el 50 % hardcodeado a parámetro (sin cambiar el flujo ENVI todavía).

3. **Motor de cálculo + períodos** (borrador → cerrada)  
   Snapshot, BigDecimal, redondeo explícito, permisos calcular/aprobar/cerrar. Casos de prueba del §8.3.8.

4. **Descuentos:** préstamos/anticipos, prelación, tope, neto ≥ 0.

5. **Compras empleado:**  
   Reutilizar `internal_shipment_request` como origen; al aprobar/entregar crear `employee_receivables` + salida de inventario (ya casi existe). Quitar o relajar el «1 al mes» si habrá cuotas. Planilla, al **pagar/cerrar**, abona CxC (idempotente).

6. **Comisiones:** esquemas nuevos (no `kiosk_promotion`). Fuente: `kiosk_sale` por `soldByUserId` → `employee_user`. Definir P4/P5 antes de codear el split.

7. **Boletas y reportes** (usar `jspdf`/`xlsx` ya en el front; plantilla PAYROLL como punto de partida).

8. **Prestaciones y liquidación** (dependen de historial salarial del paso 1).

9. **Pagos por servicios** (reporte separado; sin IGSS/prestaciones).

10. **Archivo bancario + asientos** (`accounting_entry` con `document_type=PAYROLL`; bancos: confirmar P7; hoy el POS habla de G&T e Industrial).

No avanzar de etapa sin confirmación (Fase 2).

## Preguntas que necesito que me respondas

Las del documento, más las que el código no puede resolver:

- **P1.** ¿La frecuencia es la misma para los 3 grupos? (Hoy todos tienen campos de quincena en la misma ficha.)
- **P2.** ¿HE de planta se capturan como totales o hay que ligarlas a la jornada de mesas (`ProductionShift` 9 h L–V)? Eso es otro dominio.
- **P3.** ¿Servicios también compran al 50 %? Hoy cualquier `employee` activo puede ser elegido en solicitud PLANILLA; no hay filtro por tipo (el tipo no existe).
- **P4.** ¿Comisión por vendedor (`soldByUserId`) o se reparte en el kiosco? El POS ya atribuye al usuario de la venta.
- **P5.** ¿Comisión al vender o al cobrar? Kiosco es de contado/tarjeta; el crédito CxC es otro canal (Luis Felipe / Entre Cueros).
- **P6.** ¿La venta a empleado va con FEL al 50 %? Hoy es ENVI interno + BLS, sin `tax_invoice`.
- **P7.** ¿Banco y formato de dispersión? Depósitos de kiosco usan G&T Continental e Industrial; no hay archivo de nómina.
- **P8.** ¿Tope mensual de compras / % del salario? Hoy: **una** solicitud PLANILLA por mes, sin tope en Q.
- **P9.** ¿El 50 % es sobre precio público? El código actual usa **precio de catálogo**. No usa costo.
- **P10.** ¿Varias razones sociales en una planilla? FEL muestra CUEROGLAM, S.A. (NIT 120091461) y locales con nombre comercial; empleados no tienen empresa.
- **P11.** ¿El **grupo** del empleado es `department` (Kioscos/Administración/Producción) o hay que introducir el enum del documento y migrar?
- **P12.** ¿El kiosco del empleado es `operational_unit` (UO1… CUEROGLAM …) o `locations` (POS/`encargado_id`)? Hoy no están unificados.
- **P13.** Las columnas `quincena_bruta` / `quincena_neta` / `igss_deduction` de la ficha: ¿se siguen capturando a mano hasta el motor, o se dejan de usar?
- **P14.** ¿Qué pasa con las compras PLANILLA ya entregadas sin CxC? ¿Se migran saldos o se parte de cero?
- **P15.** ¿Quién puede ver salarios hoy (solo admin/contabilidad) y quién aprobará la corrida?

## Criterios de aceptación globales (estado actual)

- [ ] Grupo × tipo de vinculación independientes — **no**. Hay departamento; no hay tipo.
- [ ] Planilla por período con IGSS/ISR/bonos/comisiones/descuentos — **no**.
- [ ] Servicios sin prestaciones y reporte aparte — **no**.
- [ ] Compra 50 % saca inventario, crea CxC y descuenta en planilla — **inventario sí; CxC y descuento de nómina no**.
- [ ] Recalcular no duplica; cierre bloquea — **no hay período**.
- [ ] Porcentajes legales no hardcodeados — **el 50 % de compra sí está hardcodeado**; IGSS es monto a mano.
- [ ] Boleta PDF — **solo variables de plantilla**.
- [ ] Pruebas de cálculos críticos — **no**.
- [ ] Bitácora y permisos de salarios — **parcial y débil en API**.

Cuando apruebes la Fase 2, la etapa 1 (ficha + contratos + parámetros) es el único punto de partida seguro: el resto depende de no seguir sobrescribiendo `employee.salary` y de no mezclar «planilla-envío» con «planilla-nómina».
