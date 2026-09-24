package com.portway.app.repo;

import com.portway.app.domain.GeneratedFileEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeneratedFileRepository extends JpaRepository<GeneratedFileEntity, UUID> {

  List<GeneratedFileEntity> findByJobIdOrderByPositionAsc(UUID jobId);

  Optional<GeneratedFileEntity> findByIdAndJobId(UUID id, UUID jobId);
}
