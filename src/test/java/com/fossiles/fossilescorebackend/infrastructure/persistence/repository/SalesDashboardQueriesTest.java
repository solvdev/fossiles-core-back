package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleHeaderRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleItemRow;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Valida contra Hibernate + H2 el JPQL de las consultas por lote del dashboard de ventas
 * (sintaxis, expresiones constructor y resultados), sin levantar Spring.
 */
class SalesDashboardQueriesTest {

    private static SessionFactory sessionFactory;

    @BeforeAll
    static void setUp() {
        StandardServiceRegistryBuilder registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", "jdbc:h2:mem:sales" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
                .applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.connection.password", "")
                .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                .applySetting("hibernate.hbm2ddl.auto", "create-drop");
        sessionFactory = new MetadataSources(registry.build())
                .addAnnotatedClass(KioskSaleEntity.class)
                .addAnnotatedClass(KioskSaleItemEntity.class)
                .addAnnotatedClass(OnlineSaleEntity.class)
                .addAnnotatedClass(OnlineSaleItemEntity.class)
                .buildMetadata()
                .buildSessionFactory();
    }

    @AfterAll
    static void tearDown() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }

    private static String jpql(Class<?> repository, String method) {
        for (Method m : repository.getMethods()) {
            if (m.getName().equals(method) && m.isAnnotationPresent(Query.class)) {
                return m.getAnnotation(Query.class).value();
            }
        }
        throw new AssertionError("Sin @Query: " + repository.getSimpleName() + "." + method);
    }

    @Test
    void kioskHeaderAndItemProjectionsRunAgainstTheMapping() {
        LocalDate day = LocalDate.of(2026, 9, 10);
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();
            KioskSaleEntity sale = KioskSaleEntity.builder()
                    .saleNumber("V-1").kioskLocationId(10L).soldByUserId(1L).saleDate(day)
                    .soldAt(LocalDateTime.of(2026, 9, 10, 12, 0)).status("COMPLETED")
                    .totalItems(BigDecimal.ONE).totalAmount(new BigDecimal("100.00")).build();
            session.persist(sale);
            session.persist(KioskSaleItemEntity.builder().kioskSale(sale).productId(1L).productCode("CIN-01")
                    .productName("Cincho T.42").quantity(BigDecimal.ONE).unitPrice(new BigDecimal("100.00"))
                    .lineTotal(new BigDecimal("100.00")).build());
            session.getTransaction().commit();
        }

        try (Session session = sessionFactory.openSession()) {
            List<KioskSaleHeaderRow> headers = session
                    .createQuery(jpql(KioskSaleRepository.class, "findHeaderRowsBySaleDateBetween"), KioskSaleHeaderRow.class)
                    .setParameter("startDate", day.minusDays(1))
                    .setParameter("endDate", day.plusDays(1))
                    .getResultList();
            assertThat(headers).hasSize(1);
            assertThat(headers.get(0).totalAmount()).isEqualByComparingTo("100.00");
            assertThat(headers.get(0).kioskLocationId()).isEqualTo(10L);

            List<KioskSaleItemRow> byRange = session
                    .createQuery(jpql(KioskSaleItemRepository.class, "findRowsBySaleDateBetween"), KioskSaleItemRow.class)
                    .setParameter("startDate", day)
                    .setParameter("endDate", day)
                    .getResultList();
            assertThat(byRange).hasSize(1);
            assertThat(byRange.get(0).kioskSaleId()).isEqualTo(headers.get(0).id());
            assertThat(byRange.get(0).productCode()).isEqualTo("CIN-01");

            List<KioskSaleItemRow> byIds = session
                    .createQuery(jpql(KioskSaleItemRepository.class, "findRowsByKioskSaleIdIn"), KioskSaleItemRow.class)
                    .setParameter("saleIds", List.of(headers.get(0).id()))
                    .getResultList();
            assertThat(byIds).hasSize(1);

            assertThat(session
                    .createQuery(jpql(KioskSaleItemRepository.class, "findRowsBySaleDateBetween"), KioskSaleItemRow.class)
                    .setParameter("startDate", day.plusDays(1))
                    .setParameter("endDate", day.plusDays(2))
                    .getResultList()).isEmpty();
        }
    }

    @Test
    void onlineItemsByDateRangeJoinOnSaleId() {
        LocalDate day = LocalDate.of(2026, 9, 12);
        Long saleId;
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();
            OnlineSaleEntity sale = OnlineSaleEntity.builder().saleNumber("ON-1").saleDate(day)
                    .totalAmount(new BigDecimal("50.00")).quantity(1).build();
            session.persist(sale);
            saleId = sale.getId();
            session.persist(OnlineSaleItemEntity.builder().onlineSaleId(saleId).productId(1L).productCode("BIL-01")
                    .productName("Billetera").quantity(1).subtotal(new BigDecimal("50.00")).build());
            session.getTransaction().commit();
        }

        try (Session session = sessionFactory.openSession()) {
            List<OnlineSaleItemEntity> inRange = session
                    .createQuery(jpql(OnlineSaleItemRepository.class, "findBySaleDateBetween"), OnlineSaleItemEntity.class)
                    .setParameter("startDate", day)
                    .setParameter("endDate", day)
                    .getResultList();
            assertThat(inRange).extracting(OnlineSaleItemEntity::getOnlineSaleId).containsExactly(saleId);

            assertThat(session
                    .createQuery(jpql(OnlineSaleItemRepository.class, "findBySaleDateBetween"), OnlineSaleItemEntity.class)
                    .setParameter("startDate", day.plusDays(1))
                    .setParameter("endDate", day.plusDays(5))
                    .getResultList()).isEmpty();
        }
    }
}
