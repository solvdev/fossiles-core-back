package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.SellerDto;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconCaseEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconSellerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.ReconCaseRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.ReconSellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.blank;

/** Catálogo de vendedores. El nombre debe ser idéntico al de online_sale.salesperson para que las comisiones se asignen. */
@Service
@RequiredArgsConstructor
public class ReconciliationSellerService {

    private final ReconSellerRepository sellerRepository;
    private final ReconCaseRepository caseRepository;
    private final OnlineSaleRepository saleRepository;
    private final ReconciliationRecomputeService recompute;
    private final ReconEventService events;
    private final ReconActor actor;

    @Transactional
    public SellerDto create(String name) throws BusinessException {
        if (blank(name)) throw new BusinessException("Escriba el nombre del vendedor, idéntico al que se usa en Ventas Online.");
        String clean = name.trim();
        if (sellerRepository.findByName(clean).isPresent()) throw new BusinessException("Ya existe un vendedor con ese nombre.");
        ReconSellerEntity s = sellerRepository.save(ReconSellerEntity.builder().name(clean).createdBy(actor.id()).build());
        // Los casos que traían este nombre y no tenían vendedor quedan asignados.
        List<ReconCaseEntity> orphans = caseRepository.findAll().stream()
                .filter(c -> c.getSellerId() == null && c.getSalesperson() != null && c.getSalesperson().trim().equals(clean) && !c.isClosed()).toList();
        orphans.forEach(c -> c.setSellerId(s.getId()));
        caseRepository.saveAll(orphans);
        recompute.recomputeAll(orphans);
        events.record(null, null, "VENDEDOR_CREADO", null, null, java.util.Map.of("nombre", clean, "casosAsignados", orphans.size()));
        return new SellerDto(s.getId(), s.getName(), s.isActive());
    }

    @Transactional
    public SellerDto setActive(Long id, boolean active) throws ResourceNotFoundException {
        ReconSellerEntity s = sellerRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Vendedor no encontrado: " + id));
        s.setActive(active);
        sellerRepository.save(s);
        return new SellerDto(s.getId(), s.getName(), s.isActive());
    }

    /** Nombres de vendedor que aparecen en ventas y no están en el catálogo (bloquean el cierre de comisiones). */
    @Transactional(readOnly = true)
    public List<String> missingFromCatalog() {
        List<String> known = sellerRepository.findAll().stream().map(s -> s.getName().trim()).toList();
        return saleRepository.findAll().stream().map(s -> s.getSalesperson()).filter(n -> !blank(n)).map(String::trim).distinct()
                .filter(n -> !known.contains(n)).sorted().toList();
    }
}
