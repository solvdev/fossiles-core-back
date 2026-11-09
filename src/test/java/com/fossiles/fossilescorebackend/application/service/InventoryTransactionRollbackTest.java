package com.fossiles.fossilescorebackend.application.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BusinessException es una excepción comprobada: con @Transactional a secas Spring la deja pasar y CONFIRMA
 * la transacción (descuentos parciales incluidos), o termina en "Transaction silently rolled back" (500) cuando
 * un servicio interno ya la marcó para deshacer. Los servicios que mueven inventario deben deshacer ante cualquier Exception.
 */
class InventoryTransactionRollbackTest {

    private static final List<Class<?>> INVENTORY_SERVICES = List.of(
            ProductInventoryService.class,
            ProductDistributionService.class,
            InventoryService.class,
            OnlineSaleService.class,
            OnlineSaleProductionOrderService.class,
            CustomerShipmentDispatchService.class,
            ProductionOrderWarehouseUnitService.class);

    @Test
    void everyWritingTransactionRollsBackOnAnyException() {
        for (Class<?> type : INVENTORY_SERVICES) {
            Transactional onClass = type.getAnnotation(Transactional.class);
            if (onClass != null && !onClass.readOnly()) {
                assertRollsBackOnException(type.getSimpleName(), onClass);
            }
            for (Method m : type.getDeclaredMethods()) {
                Transactional onMethod = m.getAnnotation(Transactional.class);
                if (onMethod != null && !onMethod.readOnly()) {
                    assertRollsBackOnException(type.getSimpleName() + "." + m.getName(), onMethod);
                }
            }
        }
    }

    private static void assertRollsBackOnException(String where, Transactional tx) {
        boolean ok = false;
        for (Class<? extends Throwable> c : tx.rollbackFor()) {
            if (c.isAssignableFrom(Exception.class)) {
                ok = true;
            }
        }
        assertTrue(ok, where + " debe declarar @Transactional(rollbackFor = Exception.class)");
    }
}
