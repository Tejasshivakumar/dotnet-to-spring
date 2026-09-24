package com.portway.app.pipeline;

import java.util.UUID;

public class JobNotFoundException extends RuntimeException {

  public JobNotFoundException(UUID jobId) {
    super("No migration job " + jobId);
  }
}
