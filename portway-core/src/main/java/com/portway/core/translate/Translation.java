package com.portway.core.translate;

import java.util.List;

/**
 * A translated method body.
 *
 * @param javaBody statements only: no signature, no braces around the whole body
 * @param requiredImports qualified names the body uses by simple name
 * @param notes the translator's own caveats, shown to the reviewer
 * @param confidence the translator's self-assessment, 0 to 1. Recorded, never trusted: only the
 *     compiler decides whether the body is kept.
 */
public record Translation(
    String javaBody, List<String> requiredImports, String notes, double confidence) {

  public Translation {
    requiredImports = List.copyOf(requiredImports == null ? List.of() : requiredImports);
  }
}
