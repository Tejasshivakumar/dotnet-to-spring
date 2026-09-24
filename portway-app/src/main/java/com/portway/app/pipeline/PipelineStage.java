package com.portway.app.pipeline;

/**
 * One step of the migration pipeline.
 *
 * <p>Stages are Spring beans. The orchestrator collects every bean of this type and runs them in
 * {@link Stage} order, so adding a stage means adding a bean and nothing else.
 */
public interface PipelineStage {

  Stage stage();

  /** @return a one-line summary of what the stage did, shown on the progress page */
  String execute(JobContext context);
}
