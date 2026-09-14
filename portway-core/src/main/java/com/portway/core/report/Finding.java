package com.portway.core.report;

/**
 * One thing the reviewer needs to know about.
 *
 * <p>Carries both ends of the mapping: where it came from in the C# source and where it landed in
 * the generated Java, so the diff view can scroll to either.
 *
 * @param sourceLine 1-based line in the C# file, or 0 when not line-specific
 * @param generatedPath may be null when nothing was generated
 * @param generatedLine may be null when nothing was generated
 */
public record Finding(
    Severity severity,
    FindingCode code,
    String message,
    String sourcePath,
    int sourceLine,
    String generatedPath,
    Integer generatedLine) {

  /** A finding at the code's default severity, anchored only in the C# source. */
  public static Finding at(FindingCode code, String message, String sourcePath, int sourceLine) {
    return new Finding(code.defaultSeverity(), code, message, sourcePath, sourceLine, null, null);
  }
}
