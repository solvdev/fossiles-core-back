package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.SpecialDeskRequest;
import com.fossiles.fossilescorebackend.application.dto.response.SpecialDeskResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionSpecialDeskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionDeskCountRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionSpecialDeskRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Alta, cambio y baja de mesas especiales (maquila). El encargado se guarda con el de las
 * demás mesas ({@link ProductionDeskSupervisorService}).
 */
@Service
@RequiredArgsConstructor
public class ProductionSpecialDeskService {

    private static final int MAX_DESK = 32;
    private static final String DEFAULT_NAME = "Maquila";
    private static final List<String> OPEN_STATUSES = List.of("PENDING", "IN_PROGRESS", "PAUSED");

    private final ProductionSpecialDeskRepository repository;
    private final ProductionDeskCountService productionDeskCountService;
    private final ProductionDeskCountRepository productionDeskCountRepository;
    private final TaskRepository taskRepository;

    public List<SpecialDeskResponse> listAll() {
        return repository.findAllByOrderByDeskAsc().stream().map(ProductionSpecialDeskService::toResponse).toList();
    }

    /** Crea o actualiza la mesa especial con ese número. */
    @Transactional
    public SpecialDeskResponse save(SpecialDeskRequest body) throws BusinessException {
        if (body == null || body.getDesk() == null) {
            throw new BusinessException("El número de mesa es obligatorio.");
        }
        int desk = body.getDesk();
        if (desk < 1 || desk > MAX_DESK) {
            throw new BusinessException("Número de mesa fuera de rango: " + desk + " (1.." + MAX_DESK + ").");
        }
        boolean active = body.getActive() == null || body.getActive();
        if (active) {
            int plantDesks = maxPlantDesksFromToday();
            if (desk <= plantDesks) {
                throw new BusinessException("La mesa " + desk + " ya es de planta (hay " + plantDesks
                        + " mesas de planta). La mesa de maquila tiene que ser un número mayor, o baje antes las mesas de planta.");
            }
        }
        String name = body.getName() == null ? "" : body.getName().trim();
        if (name.isEmpty()) {
            name = DEFAULT_NAME;
        }
        if (name.length() > 100) {
            throw new BusinessException("El nombre de la mesa es muy largo (máximo 100 letras).");
        }
        String kind = body.getKind() == null || body.getKind().isBlank()
                ? ProductionSpecialDeskEntity.KIND_MAQUILA
                : body.getKind().trim().toUpperCase(Locale.ROOT);
        if (!ProductionSpecialDeskEntity.KIND_MAQUILA.equals(kind)) {
            throw new BusinessException("Tipo de mesa especial no válido: " + kind + ".");
        }

        ProductionSpecialDeskEntity entity = repository.findByDesk(desk)
                .orElseGet(() -> ProductionSpecialDeskEntity.builder().desk(desk).build());
        if (!active && Boolean.TRUE.equals(entity.getActive()) && entity.getId() != null) {
            rejectIfOpenTasks(desk);
        }
        entity.setName(name);
        entity.setKind(kind);
        entity.setActive(active);
        return toResponse(repository.save(entity));
    }

    /** Desactiva la mesa especial. No se puede si todavía tiene tareas abiertas. */
    @Transactional
    public SpecialDeskResponse deactivate(Integer desk) throws BusinessException {
        ProductionSpecialDeskEntity entity = repository.findByDesk(desk)
                .orElseThrow(() -> new BusinessException("No existe una mesa especial con el número " + desk + "."));
        rejectIfOpenTasks(desk);
        entity.setActive(false);
        return toResponse(repository.save(entity));
    }

    private void rejectIfOpenTasks(int desk) throws BusinessException {
        long open = taskRepository.countByDeskAndStatusIn(desk, OPEN_STATUSES);
        if (open > 0) {
            throw new BusinessException("La mesa " + desk + " todavía tiene " + open
                    + " tarea(s) abiertas. Muévalas a otra mesa o termínelas antes de desactivarla.");
        }
    }

    /** Mesas de planta de hoy y de cualquier cambio ya programado hacia adelante. */
    private int maxPlantDesksFromToday() throws BusinessException {
        LocalDate today = GuatemalaDateTime.today();
        int todayDesks = productionDeskCountService.getDay(today).getNumDesks();
        Integer future = productionDeskCountRepository.findMaxNumDesksAfter(today);
        return Math.max(todayDesks, future == null ? 0 : future);
    }

    static SpecialDeskResponse toResponse(ProductionSpecialDeskEntity e) {
        return SpecialDeskResponse.builder()
                .id(e.getId())
                .desk(e.getDesk())
                .name(e.getName())
                .kind(e.getKind())
                .active(Boolean.TRUE.equals(e.getActive()))
                .build();
    }
}
