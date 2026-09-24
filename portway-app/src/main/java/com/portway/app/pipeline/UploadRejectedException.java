package com.portway.app.pipeline;

/** The uploaded archive is unusable or hostile. Maps to 400 with the reason in the detail. */
public class UploadRejectedException extends RuntimeException {

  public UploadRejectedException(String message) {
    super(message);
  }
}
