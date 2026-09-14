package com.portway.core;

import com.portway.core.report.Finding;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one migration run produced.
 *
 * @param files generated project path (relative, forward slashes) to file content, in generation
 *     order
 * @param findings every issue raised, in the order they were discovered
 */
public record MigrationResult(Map<String, String> files, List<Finding> findings) {

  public MigrationResult {
    files = Map.copyOf(files == null ? Map.of() : files);
    findings = List.copyOf(findings == null ? List.of() : findings);
  }

  public static MigrationResult of(Map<String, String> files, List<Finding> findings) {
    return new MigrationResult(new LinkedHashMap<>(files), List.copyOf(findings));
  }
}
