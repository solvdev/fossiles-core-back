package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSupervisorAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KioskSupervisorAssignmentRepository extends JpaRepository<KioskSupervisorAssignmentEntity, Long> {
    List<KioskSupervisorAssignmentEntity> findBySupervisorUserId(Long supervisorUserId);

    void deleteBySupervisorUserIdAndKioskLocationIdNotIn(Long supervisorUserId, List<Long> keepKioskLocationIds);

    void deleteBySupervisorUserId(Long supervisorUserId);
}
