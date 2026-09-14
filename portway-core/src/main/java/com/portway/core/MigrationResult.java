package com.portway.core;

import com.portway.core.report.Finding;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one migration run produced.
 *
 * <p>File order is meaningful and preserved. Generation runs in dependency order — pom, config,
 * entities, repositories, DTOs, services, controllers — and that order is what the review UI lists
 * and what a golden diff reads as. {@code Map.copyOf} would hash the keys and scramble it, so the
 * map is defensively copied into a LinkedHashMap and wrapped unmodifiable instead.
 *
 * @param files generated project path (relative, forward slashes) to file content, in generation
 *     order
 * @param findings every issue raised, in the order they were discovered
 */
public record MigrationResult(Map<String, String> files, List<Finding> findings) {

  public MigrationResult {
    files = Collections.unmodifiableMap(new LinkedHashMap<>(files == null ? Map.of() : files));
    findings = List.copyOf(findings == null ? List.of() : findings);
  }

  public static MigrationResult of(Map<String, String> files, List<Finding> findings) {
    return new MigrationResult(files, findings);
  }
}
