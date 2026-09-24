package com.portway.app.pipeline;

import com.portway.core.MigrationSession;
import com.portway.core.generate.MigrationOptions;
import com.portway.core.verify.VerifiedMigration;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * State carried from stage to stage for one job.
 *
 * <p>Owned by one pipeline thread for its whole life, so it needs no synchronisation.
 */
public final class JobContext {

  private final UUID jobId;
  private final Path workDir;
  private final Path sourceDir;
  private final MigrationOptions options;
  private final boolean aiEnabled;
  private final Consumer<Stage> stageReporter;
  private final Map<String, Object> stats = new LinkedHashMap<>();
  private MigrationSession session;
  private VerifiedMigration.Run run;
  private VerifiedMigration.Outcome outcome;

  public JobContext(
      UUID jobId,
      Path workDir,
      Path sourceDir,
      MigrationOptions options,
      boolean aiEnabled,
      Consumer<Stage> stageReporter) {
    this.jobId = jobId;
    this.workDir = workDir;
    this.sourceDir = sourceDir;
    this.options = options;
    this.aiEnabled = aiEnabled;
    this.stageReporter = stageReporter;
  }

  public UUID jobId() {
    return jobId;
  }

  /** The job's own directory, for verification builds and other scratch output. */
  public Path workDir() {
    return workDir;
  }

  /** The uploaded project's root: the directory holding its .csproj. */
  public Path sourceDir() {
    return sourceDir;
  }

  public MigrationOptions options() {
    return options;
  }

  public boolean aiEnabled() {
    return aiEnabled;
  }

  public Map<String, Object> stats() {
    return stats;
  }

  public MigrationSession session() {
    return session;
  }

  public void session(MigrationSession session) {
    this.session = session;
  }

  public VerifiedMigration.Run run() {
    return run;
  }

  public void run(VerifiedMigration.Run run) {
    this.run = run;
  }

  public VerifiedMigration.Outcome outcome() {
    return outcome;
  }

  public void outcome(VerifiedMigration.Outcome outcome) {
    this.outcome = outcome;
  }

  /** Lets a long stage report that it has moved into a sub-stage, such as VERIFY into REPAIR. */
  public void reportStage(Stage stage) {
    stageReporter.accept(stage);
  }
}
