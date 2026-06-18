package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.infrastructure.util.DeliveryRouteCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/delivery-routes")
@RequiredArgsConstructor
public class DeliveryRouteController {

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getCatalog() {
        return ResponseEntity.ok(DeliveryRouteCatalog.getCatalogTree());
    }
}
