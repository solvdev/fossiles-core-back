package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PermissionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KioskRentAccessGuardTest {

    private final SecurityUtil securityUtil = mock(SecurityUtil.class);
    private final UserRepository users = mock(UserRepository.class);
    private final KioskRentAccessGuard guard = new KioskRentAccessGuard(securityUtil, users);

    @Test
    void viewPermissionCanSeeRentsButCannotEdit() throws Exception {
        signIn(role("Contabilidad", KioskRentAccessGuard.PERM_VIEW));

        assertThatCode(guard::assertCanView).doesNotThrowAnyException();
        assertThat(guard.canEdit()).isFalse();
        assertThatThrownBy(guard::assertCanEdit)
                .isInstanceOf(BusinessException.class)
                .hasMessage("No tiene permiso para editar las rentas de los kioscos.");
    }

    @Test
    void editPermissionCanSeeAndEdit() throws BusinessException {
        signIn(role("Contabilidad", KioskRentAccessGuard.PERM_EDIT));

        assertThatCode(guard::assertCanView).doesNotThrowAnyException();
        assertThat(guard.canEdit()).isTrue();
        assertThat(guard.assertCanEdit()).isEqualTo(7L);
    }

    @Test
    void administratorDoesNotNeedExplicitRentPermissions() throws BusinessException {
        signIn(role("Administracion"));

        assertThatCode(guard::assertCanView).doesNotThrowAnyException();
        assertThat(guard.canEdit()).isTrue();
        assertThat(guard.assertCanEdit()).isEqualTo(7L);
    }

    @Test
    void otherRolesAndAnonymousUsersAreDenied() throws Exception {
        signIn(role("Logistica", "ORGANIZACION.EMPLEADOS.VER"));
        assertThatThrownBy(guard::assertCanView)
                .isInstanceOf(BusinessException.class)
                .hasMessage("No tiene permiso para ver las rentas de los kioscos.");
        assertThat(guard.canEdit()).isFalse();

        when(securityUtil.getCurrentUserId()).thenReturn(null);
        assertThatThrownBy(guard::assertCanView)
                .isInstanceOf(BusinessException.class)
                .hasMessage("No se pudo identificar el usuario autenticado.");
        assertThatThrownBy(guard::assertCanEdit)
                .isInstanceOf(BusinessException.class)
                .hasMessage("No se pudo identificar el usuario autenticado.");
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
