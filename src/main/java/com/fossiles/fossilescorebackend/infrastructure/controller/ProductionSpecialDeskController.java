package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.SpecialDeskRequest;
import com.fossiles.fossilescorebackend.application.dto.response.SpecialDeskResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.ProductionSpecialDeskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Mesas especiales (maquila). Cuelga de /desks-count para que el interceptor de caché del
 * centro de producción las invalide igual que al cambiar la cantidad de mesas.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/production/desks-count/special")
public class ProductionSpecialDeskController {

    private final ProductionSpecialDeskService productionSpecialDeskService;

    @GetMapping
    public ResponseEntity<List<SpecialDeskResponse>> list() {
        return ResponseEntity.ok(productionSpecialDeskService.listAll());
    }

    @PutMapping
    public ResponseEntity<SpecialDeskResponse> save(@RequestBody @Valid SpecialDeskRequest body) throws BusinessException {
        return ResponseEntity.ok(productionSpecialDeskService.save(body));
    }

    @DeleteMapping("/{desk}")
    public ResponseEntity<SpecialDeskResponse> deactivate(@PathVariable Integer desk) throws BusinessException {
        return ResponseEntity.ok(productionSpecialDeskService.deactivate(desk));
    }
}
