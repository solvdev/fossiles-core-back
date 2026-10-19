package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PermissionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Solo Administración y Contabilidad (por rol ADMIN o por los permisos asignados) pueden operar la conciliación. */
class ReconciliationAccessGuardTest {

    private final SecurityUtil securityUtil = Mockito.mock(SecurityUtil.class);
    private final UserRepository userRepository = Mockito.mock(UserRepository.class);
    private final ReconciliationAccessGuard guard = new ReconciliationAccessGuard(securityUtil, userRepository);

    @BeforeEach
    void loggedIn() {
        Mockito.when(securityUtil.getCurrentUserId()).thenReturn(5L);
    }

    private void userWith(String roleName, String... permissionCodes) {
        RoleEntity role = new RoleEntity();
        role.setName(roleName);
        Set<PermissionEntity> permissions = new java.util.HashSet<>();
        for (String code : permissionCodes) {
            PermissionEntity p = new PermissionEntity();
            p.setCode(code);
            permissions.add(p);
        }
        role.setPermissions(permissions);
        UserEntity user = new UserEntity();
        user.setRoles(Set.of(role));
        Mockito.when(userRepository.findById(5L)).thenReturn(Optional.of(user));
    }

    @Test
    void administracion_puedeTodo() {
        userWith("Administrador");

        assertThatCode(() -> {
            guard.assertCanView();
            guard.assertCanReconcile();
            guard.assertCanImport();
            guard.assertCanAdjust();
            guard.assertCanAuthorize();
            guard.assertCanClose();
            guard.assertCanCommissions();
        }).doesNotThrowAnyException();
    }

    @Test
    void conPermisoSoloPuedeLoQueSeLeAsigno() {
        userWith("Contabilidad", ReconciliationAccessGuard.PERM_VIEW, ReconciliationAccessGuard.PERM_CLOSE);

        assertThatCode(guard::assertCanView).doesNotThrowAnyException();
        assertThatCode(guard::assertCanClose).doesNotThrowAnyException();
        assertThatThrownBy(guard::assertCanAuthorize).isInstanceOf(BusinessException.class).hasMessageContaining("autorizar");
        assertThatThrownBy(guard::assertCanImport).isInstanceOf(BusinessException.class);
        assertThatThrownBy(guard::assertCanCommissions).isInstanceOf(BusinessException.class);
    }

    @Test
    void usuarioSinPermisos_oSinSesion_noPuedeNada() {
        userWith("Bodega");
        assertThatThrownBy(guard::assertCanView).isInstanceOf(BusinessException.class);

        Mockito.when(securityUtil.getCurrentUserId()).thenReturn(null);
        assertThatThrownBy(guard::assertCanView).isInstanceOf(BusinessException.class);
    }
}
