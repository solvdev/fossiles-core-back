package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Id del usuario que actúa. {@link SecurityUtil#getCurrentUserId()} consulta la base en cada llamada; las operaciones en
 * lote (generar mil casos, cerrar un lote) lo piden miles de veces. Aquí se resuelve una vez por sesión de seguridad
 * (la autenticación de la petición) y se reutiliza.
 */
@Component
@RequiredArgsConstructor
public class ReconActor {

    private final SecurityUtil securityUtil;
    private final Map<Authentication, Long> cache = Collections.synchronizedMap(new WeakHashMap<>());

    public Long id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return securityUtil.getCurrentUserId();
        }
        Long cached = cache.get(auth);
        if (cached != null) {
            return cached;
        }
        Long resolved = securityUtil.getCurrentUserId();
        if (resolved != null) {
            cache.put(auth, resolved);
        }
        return resolved;
    }
}
