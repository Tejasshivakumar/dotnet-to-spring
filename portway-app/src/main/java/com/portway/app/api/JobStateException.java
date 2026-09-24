package com.portway.app.api;

/** The job exists but is in the wrong state for the request, such as downloading an unfinished job. */
public class JobStateException extends RuntimeException {

  public JobStateException(String message) {
    super(message);
  }
}
