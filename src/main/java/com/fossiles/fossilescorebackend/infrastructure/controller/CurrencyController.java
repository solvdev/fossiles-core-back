package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.CurrencyRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CurrencyResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CurrencyEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CurrencyRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/currencies")
@RequiredArgsConstructor
public class CurrencyController {

    private final CurrencyRepository currencyRepository;

    @GetMapping
    public ResponseEntity<List<CurrencyResponse>> getAll() {
        List<CurrencyResponse> currencies = currencyRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(currencies);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CurrencyResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        CurrencyEntity entity = currencyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Currency", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping
    public ResponseEntity<CurrencyResponse> create(@Valid @RequestBody CurrencyRequest request) throws BusinessException {
        if (currencyRepository.findByCode(request.getCode()).isPresent()) {
            throw new BusinessException("Currency code already exists: " + request.getCode());
        }
        CurrencyEntity entity = toEntity(request);
        CurrencyEntity saved = currencyRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/currencies/" + saved.getId())).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CurrencyResponse> update(@PathVariable Long id, @Valid @RequestBody CurrencyRequest request) 
            throws ResourceNotFoundException, BusinessException {
        CurrencyEntity entity = currencyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Currency", id));
        
        if (!entity.getCode().equals(request.getCode()) 
                && currencyRepository.findByCode(request.getCode()).isPresent()) {
            throw new BusinessException("Currency code already exists: " + request.getCode());
        }
        
        entity.setCode(request.getCode());
        if (request.getName() != null) entity.setName(request.getName());
        if (request.getSymbol() != null) entity.setSymbol(request.getSymbol());
        
        CurrencyEntity updated = currencyRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        if (!currencyRepository.existsById(id)) {
            throw new ResourceNotFoundException("Currency", id);
        }
        currencyRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private CurrencyResponse toResponse(CurrencyEntity entity) {
        return CurrencyResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .symbol(entity.getSymbol())
                .build();
    }

    private CurrencyEntity toEntity(CurrencyRequest request) {
        return CurrencyEntity.builder()
                .code(request.getCode())
                .name(request.getName())
                .symbol(request.getSymbol())
                .build();
    }
}

