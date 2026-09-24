package com.portway.core;

import com.portway.core.generate.GeneratedFile;
import com.portway.core.report.Finding;
import com.portway.core.report.MethodReport;
import com.portway.core.report.Strategy;
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
 * @param generated the same files with their origin and per-file findings
 * @param methods what happened to every migrated method
 */
public record MigrationResult(
    Map<String, String> files,
    List<Finding> findings,
    List<GeneratedFile> generated,
    List<MethodReport> methods) {

  public MigrationResult {
    files = Collections.unmodifiableMap(new LinkedHashMap<>(files == null ? Map.of() : files));
    findings = List.copyOf(findings == null ? List.of() : findings);
    generated = List.copyOf(generated == null ? List.of() : generated);
    methods = List.copyOf(methods == null ? List.of() : methods);
  }

  public MigrationResult(Map<String, String> files, List<Finding> findings) {
    this(files, findings, List.of(), List.of());
  }

  public static MigrationResult of(Map<String, String> files, List<Finding> findings) {
    return new MigrationResult(files, findings);
  }

  /** Builds the result from generated files, keeping their order. */
  public static MigrationResult of(
      List<GeneratedFile> generated, List<Finding> findings, List<MethodReport> methods) {
    Map<String, String> files = new LinkedHashMap<>();
    generated.forEach(f -> files.put(f.path(), f.content()));
    return new MigrationResult(files, findings, generated, methods);
  }

  /** The methods generated into one file. */
  public List<MethodReport> methodsIn(String generatedPath) {
    return methods.stream().filter(m -> generatedPath.equals(m.generatedPath())).toList();
  }

  /**
   * The weakest strategy among a file's methods. The minimum, not the average: a file with one
   * stubbed method is not mostly fine, and the reviewer needs to know the worst thing in it.
   */
  public Strategy strategyOf(String generatedPath) {
    return methodsIn(generatedPath).stream()
        .map(MethodReport::strategy)
        .reduce(Strategy.RULE_MAPPED, Strategy::weakest);
  }

  public MethodReport method(String key) {
    return methods.stream().filter(m -> m.key().equals(key)).findFirst().orElse(null);
  }
}
