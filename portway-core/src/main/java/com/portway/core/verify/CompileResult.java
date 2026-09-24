package com.portway.core.verify;

import java.time.Duration;
import java.util.List;

/**
 * The outcome of compiling a generated project.
 *
 * @param verifier which verifier ran, shown to the reviewer: an in-process check against a fixed
 *     classpath is weaker evidence than a real Maven build, and the UI says so
 * @param log raw compiler output, kept for the report
 */
public record CompileResult(
    boolean success, List<CompileError> errors, String log, Duration elapsed, String verifier) {

  public CompileResult {
    errors = List.copyOf(errors == null ? List.of() : errors);
  }

  public List<CompileError> errorsIn(String path) {
    return errors.stream().filter(e -> path.equals(e.path())).toList();
  }
}
