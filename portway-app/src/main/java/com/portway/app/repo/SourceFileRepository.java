package com.portway.app.repo;

import com.portway.app.domain.SourceFileEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceFileRepository extends JpaRepository<SourceFileEntity, UUID> {

  List<SourceFileEntity> findByJobIdOrderByPathAsc(UUID jobId);
}
