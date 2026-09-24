package com.portway.app.pipeline;

import com.portway.app.domain.JobStage;
import com.portway.app.domain.JobStatus;
import com.portway.app.domain.MigrationJob;
import com.portway.app.repo.JobStageRepository;
import com.portway.app.repo.MigrationJobRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a job's progress and publishes it. Each call is its own transaction, so progress is
 * visible to the API while the pipeline is still running.
 */
@Component
public class JobProgress {

  private final MigrationJobRepository jobs;
  private final JobStageRepository stages;
  private final JobEvents events;

  public JobProgress(MigrationJobRepository jobs, JobStageRepository stages, JobEvents events) {
    this.jobs = jobs;
    this.stages = stages;
    this.events = events;
  }

  @Transactional
  public void started(UUID jobId) {
    MigrationJob job = job(jobId);
    job.setStatus(JobStatus.RUNNING);
    job.setStartedAt(Instant.now());
  }

  /** A stage starts: record it, move the job's current stage, tell subscribers. */
  @Transactional
  public void stageStarted(UUID jobId, Stage stage) {
    job(jobId).setCurrentStage(stage.name());
    boolean open =
        stages.findByJobIdOrderByIdAsc(jobId).stream()
            .anyMatch(s -> s.getStage().equals(stage.name()) && s.getCompletedAt() == null);
    if (!open) {
      stages.save(new JobStage(jobId, stage.name()));
    }
    events.publish(JobEvent.of(jobId, "stage", stage.name(), JobStatus.RUNNING.name(), null));
  }

  @Transactional
  public void stageCompleted(UUID jobId, Stage stage, String detail) {
    for (JobStage s : stages.findByJobIdOrderByIdAsc(jobId)) {
      boolean sameStage = s.getStage().equals(stage.name());
      // VERIFY owns the REPAIR rounds that ran inside it.
      boolean nested = stage == Stage.VERIFY && s.getStage().equals(Stage.REPAIR.name());
      if ((sameStage || nested) && s.getCompletedAt() == null) {
        s.complete(sameStage ? detail : null);
      }
    }
    events.publish(JobEvent.of(jobId, "stage-complete", stage.name(), JobStatus.RUNNING.name(), detail));
  }

  @Transactional
  public void completed(UUID jobId) {
    MigrationJob job = job(jobId);
    job.setStatus(JobStatus.COMPLETED);
    job.setCurrentStage(null);
    job.setCompletedAt(Instant.now());
    events.publish(JobEvent.of(jobId, "completed", null, JobStatus.COMPLETED.name(), null));
  }

  /** The job failed. The message is persisted and published; nothing is swallowed. */
  @Transactional
  public void failed(UUID jobId, String message) {
    jobs.findById(jobId).ifPresent(job -> {
      job.setStatus(JobStatus.FAILED);
      job.setErrorMessage(message);
      job.setCompletedAt(Instant.now());
    });
    events.publish(JobEvent.of(jobId, "failed", null, JobStatus.FAILED.name(), message));
  }

  private MigrationJob job(UUID jobId) {
    return jobs.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
  }
}
