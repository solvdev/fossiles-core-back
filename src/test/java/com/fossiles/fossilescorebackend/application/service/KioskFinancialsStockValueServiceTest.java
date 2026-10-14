package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class KioskFinancialsStockValueServiceTest {

    @Test
    void valuesPlainStockAtBasePrice() {
        var v = KioskFinancialsStockValueService.value(4, null, new BigDecimal("150.00"), true);
        assertEquals(4, v.units());
        assertEquals(0, new BigDecimal("600.00").compareTo(v.value()));
        assertEquals(0, v.unitsWithoutPrice());
        assertNull(v.sizes());
    }

    @Test
    void appliesCinchoSizeSurchargeAndPricesRemainderAtBase() {
        // 5 unidades: 2 talla 38 (sin recargo), 1 talla 46 (+50), 1 talla 52 (+100), 1 sin talla
        var v = KioskFinancialsStockValueService.value(5, "{\"38\":2,\"46\":1,\"52\":1}", new BigDecimal("200"), true);
        assertEquals(5, v.units());
        assertEquals(0, new BigDecimal("1150").compareTo(v.value()));
        assertEquals("38: 2, 46: 1, 52: 1", v.sizes());
    }

    @Test
    void sizesNeverExceedCurrentStock() {
        var v = KioskFinancialsStockValueService.value(1, "{\"52\":3}", new BigDecimal("200"), true);
        assertEquals(1, v.units());
        assertEquals(0, new BigDecimal("300").compareTo(v.value()));
    }

    @Test
    void noSurchargeWhenDisabled() {
        var v = KioskFinancialsStockValueService.value(1, "{\"52\":1}", new BigDecimal("65"), false);
        assertEquals(0, new BigDecimal("65").compareTo(v.value()));
    }

    @Test
    void unitsWithoutPriceAreCountedButAddNoValue() {
        var v = KioskFinancialsStockValueService.value(3, null, BigDecimal.ZERO, true);
        assertEquals(3, v.units());
        assertEquals(3, v.unitsWithoutPrice());
        assertEquals(0, BigDecimal.ZERO.compareTo(v.value()));
    }

    @Test
    void negativeOrNullStockIsEmpty() {
        assertEquals(0, KioskFinancialsStockValueService.value(-2, null, BigDecimal.TEN, true).units());
        assertEquals(0, KioskFinancialsStockValueService.value(null, null, BigDecimal.TEN, true).units());
    }

    @Test
    void catalogPriceFallsBackLikePos() {
        ProductEntity p = new ProductEntity();
        p.setDiscountedPrice(new BigDecimal("120"));
        p.setSellerPrice(new BigDecimal("90"));
        assertEquals(new BigDecimal("120.00"), KioskFinancialsStockValueService.catalogUnitPrice(p));
        p.setSalePrice(new BigDecimal("135.5"));
        assertEquals(new BigDecimal("135.50"), KioskFinancialsStockValueService.catalogUnitPrice(p));
        assertEquals(BigDecimal.ZERO, KioskFinancialsStockValueService.catalogUnitPrice(new ProductEntity()));
    }

    @Test
    void packagingIsDetectedByCode() {
        ProductEntity p = new ProductEntity();
        p.setCode("sum-bolsa");
        assertTrue(KioskFinancialsStockValueService.isPackaging(p));
        p.setCode("C-100");
        assertFalse(KioskFinancialsStockValueService.isPackaging(p));
    }
}
