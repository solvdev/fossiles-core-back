package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PermissionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayrollAccessGuardTest {

    private final SecurityUtil securityUtil = mock(SecurityUtil.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PayrollAccessGuard guard = new PayrollAccessGuard(securityUtil, users);

    @Test
    void administratorRolesPassWithoutExplicitPermissions() {
        signIn(role("Administracion"));
        assertThatCode(guard::assertCanApproveRuns).doesNotThrowAnyException();
        assertThatCode(guard::assertCanEditCharges).doesNotThrowAnyException();
        assertThat(guard.canViewSalaries()).isTrue();
    }

    @Test
    void eachActionNeedsItsOwnPermission() {
        signIn(role("CONTABILIDAD", PayrollAccessGuard.PERM_RUNS_VIEW, PayrollAccessGuard.PERM_CHARGES_VIEW));
        assertThatCode(guard::assertCanViewRuns).doesNotThrowAnyException();
        assertThatCode(guard::assertCanViewCharges).doesNotThrowAnyException();
        assertThat(guard.canViewSalaries()).isTrue();
        assertThatThrownBy(guard::assertCanCreateRuns).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("preparar");
        assertThatThrownBy(guard::assertCanApproveRuns).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(guard::assertCanEditCharges).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void approvingOrEditingAlsoGrantsViewing() {
        signIn(role("Planilla", PayrollAccessGuard.PERM_RUNS_APPROVE, PayrollAccessGuard.PERM_CHARGES_EDIT));
        assertThatCode(guard::assertCanViewRuns).doesNotThrowAnyException();
        assertThatCode(guard::assertCanViewCharges).doesNotThrowAnyException();
        assertThat(guard.canViewSalaries()).isTrue();
    }

    @Test
    void fullAccessPermissionsOpenEverythingLikeInTheMenu() {
        signIn(role("Gerencia", null, "ADMIN_FULL_ACCESS"));
        assertThatCode(guard::assertCanApproveRuns).doesNotThrowAnyException();
        assertThatCode(guard::assertCanEditCharges).doesNotThrowAnyException();
        assertThat(guard.canViewSalaries()).isTrue();

        signIn(role("Soporte", "SUPER_ADMIN"));
        assertThatCode(guard::assertCanCreateRuns).doesNotThrowAnyException();
    }

    @Test
    void otherRolesAndAnonymousUsersAreDeniedAndDoNotSeeSalaries() {
        signIn(role("Logistica", "ORGANIZACION.EMPLEADOS.VER", "ORGANIZACION.EMPLEADOS.EDITAR"));
        assertThatThrownBy(guard::assertCanViewRuns).isInstanceOf(AccessDeniedException.class);
        assertThat(guard.canViewSalaries()).isFalse();

        when(securityUtil.getCurrentUserId()).thenReturn(null);
        assertThatThrownBy(guard::assertCanViewCharges).isInstanceOf(AccessDeniedException.class);
        assertThat(guard.canViewSalaries()).isFalse();
    }

    private void signIn(RoleEntity... roles) {
        UserEntity user = UserEntity.builder().id(7L).username("prueba").roles(Set.of(roles)).build();
        when(securityUtil.getCurrentUserId()).thenReturn(7L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
    }

    private static RoleEntity role(String name, String... permissionCodes) {
        RoleEntity role = RoleEntity.builder().name(name).build();
        for (String code : permissionCodes) {
            role.getPermissions().add(PermissionEntity.builder().code(code).build());
        }
        return role;
    }
}
