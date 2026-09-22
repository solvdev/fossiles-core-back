package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemMaterialPickEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaskItemMaterialPickRepository extends JpaRepository<TaskItemMaterialPickEntity, Long> {

    List<TaskItemMaterialPickEntity> findByTaskItemId(Long taskItemId);

    List<TaskItemMaterialPickEntity> findByTaskItemIdIn(Collection<Long> taskItemIds);

    Optional<TaskItemMaterialPickEntity> findByTaskItemIdAndMaterialId(Long taskItemId, Long materialId);

    void deleteByTaskItemId(Long taskItemId);
}
