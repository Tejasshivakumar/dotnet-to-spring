package com.portway.app.pipeline.stages;

import com.portway.app.domain.CompileStatus;
import com.portway.app.domain.FindingEntity;
import com.portway.app.domain.GeneratedFileEntity;
import com.portway.app.domain.JobStage;
import com.portway.app.domain.MigratedMethod;
import com.portway.app.domain.MigrationJob;
import com.portway.app.domain.SourceFileEntity;
import com.portway.app.pipeline.JobContext;
import com.portway.app.pipeline.JobNotFoundException;
import com.portway.app.pipeline.PipelineStage;
import com.portway.app.pipeline.Stage;
import com.portway.app.repo.FindingRepository;
import com.portway.app.repo.GeneratedFileRepository;
import com.portway.app.repo.JobStageRepository;
import com.portway.app.repo.MigratedMethodRepository;
import com.portway.app.repo.MigrationJobRepository;
import com.portway.app.repo.SourceFileRepository;
import com.portway.core.MigrationResult;
import com.portway.core.generate.GeneratedFile;
import com.portway.core.report.Finding;
import com.portway.core.report.MethodReport;
import com.portway.core.report.Strategy;
import com.portway.core.verify.VerifiedMigration;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the outcome: every generated file with its strategy, confidence and compile status,
 * every method, every finding, and the job's headline numbers.
 *
 * <p>A file's confidence is the minimum over its methods, never the average. The reviewer needs to
 * see the worst thing in a file, and an average lets one stubbed method hide in a mostly-fine file.
 */
@Component
public class ReportStage implements PipelineStage {

  private final MigrationJobRepository jobs;
  private final SourceFileRepository sources;
  private final GeneratedFileRepository generated;
  private final MigratedMethodRepository methods;
  private final FindingRepository findings;
  private final JobStageRepository stages;

  public ReportStage(
      MigrationJobRepository jobs,
      SourceFileRepository sources,
      GeneratedFileRepository generated,
      MigratedMethodRepository methods,
      FindingRepository findings,
      JobStageRepository stages) {
    this.jobs = jobs;
    this.sources = sources;
    this.generated = generated;
    this.methods = methods;
    this.findings = findings;
    this.stages = stages;
  }

  @Override
  public Stage stage() {
    return Stage.REPORT;
  }

  @Override
  @Transactional
  public String execute(JobContext context) {
    UUID jobId = context.jobId();
    VerifiedMigration.Outcome outcome = context.outcome();
    MigrationResult result = outcome.result();

    Map<String, UUID> sourceIds =
        sources.findByJobIdOrderByPathAsc(jobId).stream()
            .collect(Collectors.toMap(SourceFileEntity::getPath, SourceFileEntity::getId, (a, b) -> a));

    Map<String, UUID> fileIds = new LinkedHashMap<>();
    int position = 0;
    for (GeneratedFile file : result.generated()) {
      Strategy strategy = result.strategyOf(file.path());
      CompileStatus status =
          switch (outcome.fileStatus().getOrDefault(file.path(), VerifiedMigration.FileStatus.NOT_RUN)) {
            case PASS -> CompileStatus.PASS;
            case FAIL -> CompileStatus.FAIL;
            case NOT_RUN -> CompileStatus.NOT_RUN;
          };
      GeneratedFileEntity entity =
          generated.save(
              new GeneratedFileEntity(
                  jobId,
                  file.sourcePath() == null ? null : sourceIds.get(file.sourcePath()),
                  file.path(),
                  position++,
                  file.content(),
                  strategy.name(),
                  BigDecimal.valueOf(strategy.confidence()).setScale(2, RoundingMode.HALF_UP),
                  status));
      fileIds.put(file.path(), entity.getId());
    }

    for (MethodReport m : result.methods()) {
      methods.save(
          new MigratedMethod(
              jobId,
              fileIds.get(m.generatedPath()),
              m.key(),
              m.javaSignature(),
              m.strategy().name(),
              m.tier() == null ? null : m.tier().name(),
              m.reason(),
              m.sourcePath(),
              m.startLine(),
              m.endLine()));
    }

    Map<String, String> generatedBySource = new LinkedHashMap<>();
    result.generated().forEach(f -> {
      if (f.sourcePath() != null) {
        generatedBySource.putIfAbsent(f.sourcePath(), f.path());
      }
    });
    for (Finding f : outcome.findings()) {
      String path = f.generatedPath() != null ? f.generatedPath() : generatedBySource.get(f.sourcePath());
      findings.save(
          new FindingEntity(
              jobId,
              path == null ? null : fileIds.get(path),
              f.severity().name(),
              f.code().name(),
              f.message(),
              f.sourcePath(),
              f.sourceLine() > 0 ? f.sourceLine() : null,
              f.generatedLine()));
    }

    MigrationJob job = jobs.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
    Map<String, Object> stats = new LinkedHashMap<>(context.stats());
    stats.putAll(summary(outcome));
    stats.put("stageMillis", stageMillis(jobId));
    job.setStats(stats);
    if (outcome.compile() != null) {
      job.setVerifier(outcome.compile().verifier());
      job.setCompileStatus(outcome.compiles() ? CompileStatus.PASS : CompileStatus.FAIL);
    }
    return result.files().size() + " files, " + result.methods().size() + " methods and "
        + outcome.findings().size() + " findings recorded";
  }

  static Map<String, Object> summary(VerifiedMigration.Outcome outcome) {
    MigrationResult result = outcome.result();
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("files", result.files().size());

    Map<Strategy, Long> byStrategy = new EnumMap<>(Strategy.class);
    for (Strategy s : Strategy.values()) {
      byStrategy.put(s, 0L);
    }
    result.methods().forEach(m -> byStrategy.merge(m.strategy(), 1L, Long::sum));
    stats.put("methods", result.methods().size());
    stats.put("methodsByStrategy", toNames(byStrategy));

    stats.put("findingsBySeverity", outcome.findings().stream()
        .collect(Collectors.groupingBy(f -> f.severity().name(), TreeMap::new, Collectors.counting())));
    stats.put("findingsByCode", outcome.findings().stream()
        .collect(Collectors.groupingBy(f -> f.code().name(), TreeMap::new, Collectors.counting())));

    Map<String, Object> compile = new LinkedHashMap<>();
    if (outcome.compile() == null) {
      compile.put("status", CompileStatus.NOT_RUN.name());
    } else {
      compile.put("status", outcome.compiles() ? "PASS" : "FAIL");
      compile.put("verifier", outcome.compile().verifier());
      compile.put("rounds", outcome.rounds());
      compile.put("errors", outcome.compile().errors().size());
      compile.put("millis", outcome.compile().elapsed().toMillis());
    }
    stats.put("compile", compile);
    return stats;
  }

  private Map<String, Long> stageMillis(UUID jobId) {
    Map<String, Long> millis = new LinkedHashMap<>();
    for (JobStage s : stages.findByJobIdOrderByIdAsc(jobId)) {
      if (s.getCompletedAt() != null) {
        millis.merge(s.getStage(), Duration.between(s.getStartedAt(), s.getCompletedAt()).toMillis(), Long::sum);
      }
    }
    return millis;
  }

  private static <E extends Enum<E>> Map<String, Long> toNames(Map<E, Long> map) {
    return map.entrySet().stream()
        .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
  }

  static <T> Map<String, List<T>> group(List<T> items, Function<T, String> key) {
    return items.stream().collect(Collectors.groupingBy(key, TreeMap::new, Collectors.toList()));
  }
}
