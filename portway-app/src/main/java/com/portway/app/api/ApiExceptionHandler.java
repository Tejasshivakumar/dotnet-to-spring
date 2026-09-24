package com.portway.app.api;

import com.portway.app.pipeline.JobNotFoundException;
import com.portway.app.pipeline.UploadRejectedException;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps failures to RFC 7807 problem details. Framework errors (validation, oversized uploads,
 * malformed requests) are handled by the base class; these are Portway's own.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  @ExceptionHandler({JobNotFoundException.class, NotFoundException.class})
  ProblemDetail notFound(RuntimeException e) {
    return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
  }

  @ExceptionHandler(UploadRejectedException.class)
  ProblemDetail uploadRejected(UploadRejectedException e) {
    return problem(HttpStatus.BAD_REQUEST, "Upload rejected", e.getMessage());
  }

  /**
   * Portway's own validation only. A bare IllegalArgumentException from deeper down is a server
   * bug, and reporting it as the client's fault would hide it.
   */
  @ExceptionHandler(InvalidRequestException.class)
  ProblemDetail badArgument(InvalidRequestException e) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
  }

  @ExceptionHandler(JobStateException.class)
  ProblemDetail conflict(JobStateException e) {
    return problem(HttpStatus.CONFLICT, "Job not in the right state", e.getMessage());
  }

  /** The pipeline queue is full. Better to say so now than to accept work that will wait. */
  @ExceptionHandler(TaskRejectedException.class)
  ProblemDetail busy(TaskRejectedException e) {
    ProblemDetail problem =
        problem(HttpStatus.SERVICE_UNAVAILABLE, "Pipeline busy", "Too many migrations in progress. Try again shortly.");
    problem.setProperty("retryAfterSeconds", 30);
    return problem;
  }

  private static ProblemDetail problem(HttpStatus status, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    return problem;
  }
}
