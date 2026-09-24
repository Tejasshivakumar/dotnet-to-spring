package com.portway.app.repo;

import com.portway.app.domain.MigratedMethod;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigratedMethodRepository extends JpaRepository<MigratedMethod, UUID> {

  List<MigratedMethod> findByJobId(UUID jobId);

  List<MigratedMethod> findByGeneratedFileId(UUID generatedFileId);
}
