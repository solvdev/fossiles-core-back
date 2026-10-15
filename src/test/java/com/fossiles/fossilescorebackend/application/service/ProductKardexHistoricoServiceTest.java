package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.ProductKardexHistoricoResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryKardexHistorico;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryKardexHistoricoRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductKardexHistoricoServiceTest {

    @Autowired private ProductKardexHistoricoService service;
    @Autowired private ProductInventoryKardexHistoricoRepository historicoRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private LocationRepository locationRepository;

    private ProductEntity product;
    private LocationEntity bodega;
    private LocationEntity devoluciones;

    @BeforeEach
    void setUp() {
        product = productRepository.save(ProductEntity.builder()
                .code("HIST-01").name("Billetera historica").requiresMaterials(false).build());
        bodega = locationRepository.save(LocationEntity.builder()
                .code("BODEGA_PT").name("Bodega PT").categoria("BODEGA_PT").build());
        devoluciones = locationRepository.save(LocationEntity.builder()
                .code("DEVOLUCION").name("Devoluciones").categoria("DEVOLUCION").build());

        save(1001L, bodega, "PRODUCTION_ENTRY", "10", LocalDateTime.of(2026, 9, 1, 10, 0), "OP-1", "LIMPIEZA-A");
        save(1002L, bodega, "SHIPMENT", "-4", LocalDateTime.of(2026, 9, 15, 10, 0), "ENV-00529", "LIMPIEZA-A");
        save(1003L, devoluciones, "RETURN", "2", LocalDateTime.of(2026, 10, 1, 10, 0), "V-77", "LIMPIEZA-A");
    }

    @Test
    void busca_porUbicacion_ordenadoDelMasReciente() {
        ProductKardexHistoricoResponse.Page page = service.search(
                bodega.getId(), null, null, null, null, null, null, 0, 50);

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(ProductKardexHistoricoResponse.Row::getId)
                .containsExactly(1002L, 1001L);
        assertThat(page.getContent().get(0).getProductCode()).isEqualTo("HIST-01");
        assertThat(page.getContent().get(0).getLocationName()).isEqualTo("Bodega PT");
    }

    @Test
    void filtra_porFechasYReferencia() {
        ProductKardexHistoricoResponse.Page porFecha = service.search(
                null, product.getId(), null, null, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 1),
                null, 0, 50);
        assertThat(porFecha.getContent()).extracting(ProductKardexHistoricoResponse.Row::getId)
                .containsExactly(1003L, 1002L);

        ProductKardexHistoricoResponse.Page porReferencia = service.search(
                null, null, null, null, null, null, "env-005", 0, 50);
        assertThat(porReferencia.getContent()).extracting(ProductKardexHistoricoResponse.Row::getId)
                .containsExactly(1002L);
    }

    @Test
    void filtros_listanLimpiezasYTipos() {
        ProductKardexHistoricoResponse.Filters filters = service.filters();

        assertThat(filters.getArchives()).hasSize(1);
        assertThat(filters.getArchives().get(0).getArchiveReference()).isEqualTo("LIMPIEZA-A");
        assertThat(filters.getArchives().get(0).getMovements()).isEqualTo(3);
        assertThat(filters.getMovementTypes()).containsExactly("PRODUCTION_ENTRY", "RETURN", "SHIPMENT");
    }

    private void save(Long id, LocationEntity location, String type, String qty, LocalDateTime date,
            String reference, String archive) {
        historicoRepository.save(ProductInventoryKardexHistorico.builder()
                .id(id)
                .productId(product.getId())
                .locationId(location.getId())
                .movementType(type)
                .quantity(new BigDecimal(qty))
                .movementDate(date)
                .referenceNumber(reference)
                .archiveReference(archive)
                .archivedAt(LocalDateTime.of(2026, 10, 20, 18, 0))
                .build());
    }
}
