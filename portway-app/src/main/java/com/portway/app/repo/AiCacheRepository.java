package com.portway.app.repo;

import com.portway.app.domain.AiCacheEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiCacheRepository extends JpaRepository<AiCacheEntry, String> {}
