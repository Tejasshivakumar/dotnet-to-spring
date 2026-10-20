package com.portway.app.api;

import com.portway.app.domain.CompileStatus;
import com.portway.app.domain.FindingEntity;
import com.portway.app.domain.GeneratedFileEntity;
import com.portway.app.domain.JobStatus;
import com.portway.app.domain.MigrationJob;
import com.portway.app.pipeline.JobEvent;
import com.portway.app.pipeline.JobEvents;
import com.portway.app.pipeline.JobNotFoundException;
import com.portway.app.repo.FindingRepository;
import com.portway.app.repo.GeneratedFileRepository;
import com.portway.app.repo.MigrationJobRepository;
import com.portway.core.report.FindingCode;
import com.portway.core.verify.CompileError;
import com.portway.core.verify.CompileResult;
import com.portway.core.verify.CompileVerifier;
import com.portway.core.verify.ProjectWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Compiles a job's files as they stand after review: edits included, rejected files excluded.
 *
 * <p>An edit is untrusted in exactly the way generated code is. Until this runs, an edited file
 * shows NOT_RUN rather than keeping a PASS it did not earn.
 */
@Service
public class ReverifyService {

  private static final Logger log = LoggerFactory.getLogger(ReverifyService.class);

  private final JobService jobService;
  private final CompileVerifier verifier;
  private final TaskExecutor executor;
  private final MigrationJobRepository jobs;
  private final GeneratedFileRepository files;
  private final FindingRepository findings;
  private final JobEvents events;
  private final TransactionTemplate transaction;

  public ReverifyService(
      JobService jobService,
      CompileVerifier verifier,
      @Qualifier("pipelineExecutor") TaskExecutor executor,
      MigrationJobRepository jobs,
      GeneratedFileRepository files,
      FindingRepository findings,
      JobEvents events,
      PlatformTransactionManager transactionManager) {
    this.jobService = jobService;
    this.verifier = verifier;
    this.executor = executor;
    this.jobs = jobs;
    this.files = files;
    this.findings = findings;
    this.events = events;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /** Queues a verification of the current files. Fails fast if the job is not complete. */
  public void submit(UUID jobId) {
    Map<String, String> current = transaction.execute(status -> {
      MigrationJob job = jobs.findForUpdate(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
      Map<String, String> snapshot = jobService.currentFiles(jobId);
      job.setStatus(JobStatus.RUNNING);
      job.setCurrentStage("VERIFY");
      job.setCompileStatus(CompileStatus.NOT_RUN);
      files.findByJobIdOrderByPositionAsc(jobId).forEach(f -> f.setCompileStatus(CompileStatus.NOT_RUN));
      return snapshot;
    });
    // Commit RUNNING before dispatch: concurrent reviews, deletes and verifies must wait.
    try {
      executor.execute(() -> {
        try {
          run(jobId, current);
        } catch (RuntimeException e) {
          log.error("Re-verification of job {} failed", jobId, e);
          CompileResult failed = new CompileResult(false,
              List.of(new CompileError(null, 0, 0, "Verification failed: " + e.getMessage())),
              "", Duration.ZERO, verifier.name());
          transaction.executeWithoutResult(status -> record(jobId, current, failed));
          events.publish(JobEvent.of(jobId, "completed", "VERIFY", "FAIL", e.getMessage()));
        }
      });
    } catch (RuntimeException e) {
      transaction.executeWithoutResult(status -> jobs.findForUpdate(jobId)
          .ifPresent(job -> job.setStatus(JobStatus.COMPLETED)));
      throw e;
    }
  }

  void run(UUID jobId, Map<String, String> current) {
    Path dir;
    try {
      dir = Files.createTempDirectory("portway-reverify");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    CompileResult result;
    try {
      ProjectWriter.write(current, dir);
      result = verifier.verify(dir);
    } finally {
      JobService.deleteQuietly(dir);
    }
    transaction.executeWithoutResult(status -> record(jobId, current, result));
    events.publish(JobEvent.of(jobId, "completed", "VERIFY", result.success() ? "PASS" : "FAIL",
        result.errors().size() + " errors"));
  }

  private void record(UUID jobId, Map<String, String> current, CompileResult result) {
    MigrationJob job = jobs.findForUpdate(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
    job.setStatus(JobStatus.COMPLETED);
    job.setCompileStatus(result.success() ? CompileStatus.PASS : CompileStatus.FAIL);
    job.setVerifier(result.verifier());

    findings.deleteByJobIdAndCode(jobId, FindingCode.COMPILE_ERROR.name());
    Map<String, GeneratedFileEntity> byPath = new java.util.HashMap<>();
    for (GeneratedFileEntity file : files.findByJobIdOrderByPositionAsc(jobId)) {
      byPath.put(file.getPath(), file);
      if (!file.getPath().endsWith(".java")) {
        continue;
      }
      if (!current.containsKey(file.getPath())) {
        file.setCompileStatus(CompileStatus.NOT_RUN);
      } else {
        boolean globalError = !result.success()
            && (result.errors().isEmpty() || result.errors().stream().anyMatch(e -> e.path() == null));
        file.setCompileStatus(globalError ? CompileStatus.NOT_RUN
            : result.errorsIn(file.getPath()).isEmpty() ? CompileStatus.PASS : CompileStatus.FAIL);
      }
    }
    for (CompileError error : result.errors()) {
      GeneratedFileEntity file = error.path() == null ? null : byPath.get(error.path());
      findings.save(
          new FindingEntity(
              jobId,
              file == null ? null : file.getId(),
              "HIGH",
              FindingCode.COMPILE_ERROR.name(),
              "Does not compile: " + error.message().lines().findFirst().orElse(error.message()),
              null,
              null,
              error.line() > 0 ? error.line() : null));
    }
  }
}
