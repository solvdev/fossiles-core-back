# Mapping: shipment numbers (LF OPV / LF OPC / LF cinchos / Entrecueros / kiosk / ENVI / ENVL)

Source: `ProductDistributionService.createOrUpdateShipmentForProductionOrder` number selection
(`src/main/java/com/fossiles/fossilescorebackend/application/service/ProductDistributionService.java`
around lines 331–340). ENVL is assigned outside this method.

`isLuisFelipeVendorOrder(order)` in this class is **seller name contains `"LUIS FELIPE"`**
(lines 5141–5147). It does **not** use `ProductionPlanningConstants.isLuisFelipeVendorOrder`
(that one also requires OPV family / `OPV-` code).

`isCincho` is `isCinchoOrderType(orderType)`: `CINCHOS`, `CINCHOS_FOSSILES`, `CINCHOS_MARCAS`
(lines 950–956).

`isEntreCuerosCustomerOpv(order)`: OPV family (`MARCAS` / `OPV` / code `OPV-*`), not INTERNA /
CLIENTE_KIOSKO / cincho type, customer name contains Entrecueros after accent-stripping
(lines 5158–5172).

## Selection (first match wins) — after this PR

| Label | Code condition | Method | Format |
| --- | --- | --- | --- |
| **Kiosk / distribution** | `location != null` | `generateShipmentNumber(location)` | `{KIOSK_CODE}-ENV-{nnnnn}` per location (unchanged) |
| **LF OPC / LF cinchos** | `else if (isLuisFelipeVendorOrder && isCincho)` | `ensureLfEnvpOnOrderIfMissing` then `generateOpcShipmentNumber` | Shipment `{OPCODE}-ENV-{nnnnn}` from counter `LF_OPC`. Order `vendor_shipment_number` gets `ENVP-nnnnn` from `LF_ENVP` only if null (never OPC-ENV fallback, never overwrite) |
| **LF OPV** | `else if (isLuisFelipeVendorOrder)` | `allocateLfEnvpShipmentNumber` | `ENVP-nnnnn` from `LF_ENVP` on `product_shipment.shipment_number`. Copied to the OP only if null |
| **Entrecueros / non-LF OPC** | `else if (isCincho \|\| isEntreCuerosCustomerOpv)` | `generateOpcShipmentNumber` | `{OPCODE}-ENV-{nnnnn}` per-order max+1 (unchanged) |
| **ENVI** | `else` | `generateShipmentNumberForOpiDocument` | `ENVI-nnnnn` (unchanged) |
| **ENVL** | not in this branch | `OnlineSaleShipmentNumberService.assignIfMissing` | `ENVL-nnnnn` (unchanged) |

Counters: `shipment_number_counter` rows `LF_ENVP` / `LF_OPC`. `UPDATE … RETURNING last_value` in the shipment transaction. Missing row → Spanish `BusinessException`.

No early reservation: `ProductionOrderController` no longer calls `assignIfMissing` on create/update/GET.

## Rotation

Luis Felipe does **not** rotate `vendor_shipment_number` (first ENVP, never overwritten).
ENVI still rotates when the cancelled shipment used the OP number and no other active shipment remains.

## Unchanged by design

Kiosk/distribution per location, ENVI, ENVL, Entrecueros, non-LF OPC.
