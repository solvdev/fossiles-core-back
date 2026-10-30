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

## Selection (first match wins)

| Label | Code condition | Method | Format |
| --- | --- | --- | --- |
| **Kiosk / distribution** | `location != null` (`request.locationId` resolved to a `LocationEntity`) | `generateShipmentNumber(location)` ~4576 | `{KIOSK_CODE}-ENV-{nnnnn}` from max trailing sequence of shipments for that `locationId` |
| **LF OPV** and **LF cinchos** | `else if (isLuisFelipeVendorOrder(order))` — any Luis Felipe seller, including cincho types | `ensureOpvVendorShipmentNumberOnOrder(order)` ~5068 then `allocateLfCinchoPhysicalShipmentNumber(order)` ~5044 | See below |
| **LF OPC** (non-LF seller) / **Entrecueros** / non-LF cinchos | `else if (isCincho \|\| isEntreCuerosCustomerOpv(order))` | `generateOpcShipmentNumber(order)` ~1201 | `{OPCODE}-ENV-{nnnnn}` per-order max trailing sequence + 1 |
| **ENVI** (OPI / leftover) | `else` | `generateShipmentNumberForOpiDocument(order)` ~4559 | `ENVI-nnnnn` via `OpiVendorShipmentNumberService` |
| **ENVL** | not in this branch | `OnlineSaleShipmentNumberService.assignIfMissing` | `ENVL-nnnnn` on `online_sale.shipment_number` |

Luis Felipe OPC (cinchos) **never** reach `generateOpcShipmentNumber`: they match the LF branch first.

## LF OPV / LF cinchos (shared branch)

1. `ensureOpvVendorShipmentNumberOnOrder` ~5068
   - `rotateVendorShipmentNumberIfTaken` ~5100: if `production_order.vendor_shipment_number` already exists as `product_shipment.shipment_number`, calls `assignNextVendorShipmentNumber`.
   - `OpvVendorShipmentNumberService.assignIfMissing`: if the OP has no vendor number, scans max `ENVP-(\d+)` in `production_order.vendor_shipment_number` and `product_shipment.shipment_number` (`findEnvpPrefixShipmentNumbers`) and writes `ENVP-nnnnn` on the **order**.
   - `reconcileVendorNumberIfColliding`: if that ENVP is already a shipment of **another** OP, allocates the next free ENVP.
2. `allocateLfCinchoPhysicalShipmentNumber` ~5044 writes **`product_shipment.shipment_number`**:
   - prefix = order `vendor_shipment_number` if present, else sanitized `order.code` (OPC-ENV fallback).
   - `nextUniqueShipmentNumberWithPrefix(orderId, prefix + "-ENV-")` ~5052 → `{ENVP-nnnnn}-ENV-{nnnnn}` (or `{OPCODE}-ENV-{nnnnn}` if no ENVP).
3. Early reservation (not in the 331–340 branch): `ProductionOrderController` `assignIfMissing` on create ~371, update ~454, GET-by-id `ensureOpvVendorShipmentNumber` ~2083.

## Rotation (~5077–5122)

- `rotateVendorNumberAfterCancellingShipment` ~5082: if the cancelled shipment used the OP vendor number and no other active (non-DRAFT, non-CANCELLED) shipment remains, `assignNextVendorShipmentNumber`.
- `assignNextVendorShipmentNumber` ~5113: LF seller → `opvVendorShipmentNumberService.nextNumber()` (`ENVP-nnnnn`); otherwise `opiVendorShipmentNumberService.nextNumber()` (`ENVI-nnnnn`).
- `vendorNumberAppliesToShipment` ~5125: shipment number equals the vendor number or starts with `{vendor}-`.

## Unchanged by design (this PR)

Kiosk/distribution per location, ENVI, ENVL, Entrecueros, non-LF OPC stay on the generators above.
LF cinchos keep the physical `{ENVP}-ENV-n` allocator; the OPC-ENV fallback when the OP has no ENVP is removed in the numbering step (counter instead).
