package com.portway.app.repo;

import com.portway.app.domain.JobStage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobStageRepository extends JpaRepository<JobStage, Long> {

  List<JobStage> findByJobIdOrderByIdAsc(UUID jobId);
}
