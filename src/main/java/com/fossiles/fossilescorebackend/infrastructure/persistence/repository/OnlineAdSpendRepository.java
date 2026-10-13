package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineAdSpendEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface OnlineAdSpendRepository extends JpaRepository<OnlineAdSpendEntity, Long> {

    List<OnlineAdSpendEntity> findBySpendDateBetweenOrderBySpendDateAsc(LocalDate startDate, LocalDate endDate);

    Optional<OnlineAdSpendEntity> findBySpendDate(LocalDate spendDate);

    /** Capturas existentes de un lote de días (para el guardado masivo). */
    List<OnlineAdSpendEntity> findBySpendDateIn(Collection<LocalDate> spendDates);

    /** Devuelve cuántos registros borró (0 si ese día no tenía captura). */
    long deleteBySpendDate(LocalDate spendDate);
}
