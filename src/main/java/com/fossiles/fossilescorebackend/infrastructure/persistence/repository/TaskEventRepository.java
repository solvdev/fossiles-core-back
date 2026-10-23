package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskEventRepository extends JpaRepository<TaskEventEntity, Long> {
    List<TaskEventEntity> findByTaskIdOrderByCreatedAtAscIdAsc(Long taskId);
}
