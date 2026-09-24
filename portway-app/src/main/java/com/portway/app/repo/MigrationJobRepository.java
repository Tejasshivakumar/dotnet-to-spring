package com.portway.app.repo;

import com.portway.app.domain.MigrationJob;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationJobRepository extends JpaRepository<MigrationJob, UUID> {}
