package com.fossiles.fossilescorebackend.infrastructure.persistence;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CostCenterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DepartmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OperationalUnitEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PermissionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Modificar usuario" en produccion: user_role tiene su propia PK (id) y desde agosto una restriccion unica
 * uq_user_role. Se reproduce el flujo real: el usuario se carga con roles y permisos (EntityGraph) en la misma
 * transaccion, se arma una entidad nueva desde el dominio (UserMapper.toEntity) y se guarda con merge.
 * La tabla se deja SIN restriccion unica para ver si Hibernate reinserta filas existentes.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user_role_repro2;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = UserRoleUpdateReproTest.Cfg.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserRoleUpdateReproTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackages = "repro.sin.repositorios")
    static class Cfg {
        @Bean
        PersistenceManagedTypes managedTypes() {
            return PersistenceManagedTypes.of(UserEntity.class.getName(), RoleEntity.class.getName(),
                    PermissionEntity.class.getName(), DepartmentEntity.class.getName(),
                    CostCenterEntity.class.getName(), OperationalUnitEntity.class.getName());
        }
    }

    @PersistenceContext EntityManager em;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void modificarUsuarioCargadoConRolesYPermisosNoDebeReinsertarSusRoles() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        long[] ids = new long[3];
        tx.executeWithoutResult(s -> {
            List<PermissionEntity> perms = new java.util.ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                PermissionEntity p = PermissionEntity.builder().code("P." + i).module("M").action("VER").build();
                em.persist(p);
                perms.add(p);
            }
            RoleEntity r6 = RoleEntity.builder().name("ROL6").description("d")
                    .permissions(new HashSet<>(perms.subList(0, 4))).build();
            RoleEntity r11 = RoleEntity.builder().name("ROL11").description("d")
                    .permissions(new HashSet<>(perms.subList(2, 6))).build();
            em.persist(r6);
            em.persist(r11);
            UserEntity u = UserEntity.builder().username("u17").email("u17@x.com").password("x").status("active")
                    .roles(new HashSet<>(Set.of(r6, r11))).build();
            em.persist(u);
            ids[0] = u.getId();
            ids[1] = r6.getId();
            ids[2] = r11.getId();
        });
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM user_role WHERE user_id = ?", Integer.class, ids[0]);
        assertThat(before).isEqualTo(2);
        jdbc.execute("ALTER TABLE user_role DROP PRIMARY KEY"); // como produccion hasta agosto: sin restriccion unica

        // ---- UserService.updateUser (@Transactional): findById con EntityGraph + toDomain + toEntity + save(merge)
        tx.executeWithoutResult(s -> {
            UserEntity managed = em.createQuery(
                    "select distinct u from UserEntity u left join fetch u.roles r left join fetch r.permissions "
                            + "where u.id = :id", UserEntity.class).setParameter("id", ids[0]).getSingleResult();

            // dominio -> entidad nueva (como UserMapper.toEntity / RoleMapper.toEntity: roles y permisos copiados)
            Set<RoleEntity> rolesCopy = managed.getRoles().stream().map(r -> RoleEntity.builder()
                    .id(r.getId()).name(r.getName()).description(r.getDescription())
                    .permissions(r.getPermissions().stream().map(p -> PermissionEntity.builder().id(p.getId())
                            .code(p.getCode()).description(p.getDescription()).module(p.getModule())
                            .routePath(p.getRoutePath()).action(p.getAction()).build()).collect(Collectors.toSet()))
                    .build()).collect(Collectors.toSet());
            UserEntity detached = UserEntity.builder().id(ids[0]).username("nuevo_usuario").email("u17@x.com")
                    .password("otra").status("active").roles(rolesCopy).build();

            em.merge(detached);
        });

        List<Integer> roleRows = jdbc.queryForList(
                "SELECT role_id FROM user_role WHERE user_id = ? ORDER BY role_id", Integer.class, ids[0]);
        System.out.println("user_role del usuario despues de modificarlo: " + roleRows);
        // regresion: modificar un usuario NO debe reinsertar sus roles (filas repetidas; en produccion, uq_user_role)
        assertThat(roleRows).containsExactly((int) ids[1], (int) ids[2]);
    }
}
