package com.portway.app.repo;

import com.portway.app.domain.FindingEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FindingRepository extends JpaRepository<FindingEntity, UUID> {

  List<FindingEntity> findByJobId(UUID jobId);

  List<FindingEntity> findByGeneratedFileId(UUID generatedFileId);

  @Modifying
  @Query("delete from FindingEntity f where f.jobId = :jobId and f.code = :code")
  int deleteByJobIdAndCode(UUID jobId, String code);
}
