package com.portway.app.repo;

import com.portway.app.domain.MigrationJob;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MigrationJobRepository extends JpaRepository<MigrationJob, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select j from MigrationJob j where j.id = :id")
  Optional<MigrationJob> findForUpdate(@Param("id") UUID id);
}
