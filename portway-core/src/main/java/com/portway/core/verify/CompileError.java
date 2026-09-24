package com.portway.core.verify;

/**
 * One compiler error, anchored in the generated project.
 *
 * @param path generated project path, forward slashes, e.g. {@code src/main/java/a/B.java}; null
 *     for errors not tied to a file
 * @param line 1-based, or 0 when unknown
 */
public record CompileError(String path, int line, int column, String message) {

  @Override
  public String toString() {
    return (path == null ? "" : path + ":" + line + ":" + column + " ") + message;
  }
}
