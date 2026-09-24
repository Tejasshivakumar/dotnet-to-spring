package com.portway.app.pipeline;

import java.time.Instant;
import java.util.UUID;

/**
 * One progress event on a job's SSE stream.
 *
 * @param type {@code snapshot} on subscribe, {@code stage} when a stage starts, {@code
 *     stage-complete} when it ends, then {@code completed} or {@code failed} last
 */
public record JobEvent(
    UUID jobId, String type, String stage, String status, String detail, Instant at) {

  public static JobEvent of(UUID jobId, String type, String stage, String status, String detail) {
    return new JobEvent(jobId, type, stage, status, detail, Instant.now());
  }

  public boolean terminal() {
    return type.equals("completed") || type.equals("failed");
  }
}
