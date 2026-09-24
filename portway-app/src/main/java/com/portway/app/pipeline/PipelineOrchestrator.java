package com.portway.app.pipeline;

import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Runs every {@link PipelineStage} bean, in {@link Stage} order, on the bounded pipeline executor.
 *
 * <p>A stage that throws fails the job: the message is stored, a terminal event is pushed, and the
 * remaining stages do not run. Nothing is swallowed and nothing is retried silently.
 */
@Service
public class PipelineOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

  private final List<PipelineStage> stages;
  private final JobProgress progress;
  private final TaskExecutor executor;

  public PipelineOrchestrator(
      List<PipelineStage> stages, JobProgress progress, @Qualifier("pipelineExecutor") TaskExecutor executor) {
    this.stages = stages.stream().sorted(Comparator.comparing(PipelineStage::stage)).toList();
    this.progress = progress;
    this.executor = executor;
  }

  /**
   * Queues a job. Throws {@link org.springframework.core.task.TaskRejectedException} when the
   * executor's queue is full, which the API turns into 503.
   */
  public void submit(JobContext context) {
    executor.execute(() -> run(context));
  }

  /** The stage order actually used. */
  public List<Stage> order() {
    return stages.stream().map(PipelineStage::stage).toList();
  }

  void run(JobContext context) {
    Stage current = null;
    try {
      progress.started(context.jobId());
      for (PipelineStage stage : stages) {
        current = stage.stage();
        progress.stageStarted(context.jobId(), current);
        String detail = stage.execute(context);
        progress.stageCompleted(context.jobId(), current, detail);
      }
      progress.completed(context.jobId());
    } catch (RuntimeException | Error e) {
      String where = current == null ? "" : " during " + current;
      log.error("Job {} failed{}", context.jobId(), where, e);
      progress.failed(context.jobId(), e.getClass().getSimpleName() + where + ": " + e.getMessage());
      if (e instanceof Error error && !(e instanceof AssertionError)) {
        throw error;
      }
    }
  }
}
