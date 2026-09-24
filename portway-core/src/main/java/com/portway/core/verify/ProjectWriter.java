package com.portway.core.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Writes generated files to disk, refusing any path that would escape the target directory. */
public final class ProjectWriter {

  private ProjectWriter() {}

  public static void write(Map<String, String> files, Path root) {
    Path base = root.toAbsolutePath().normalize();
    try {
      for (Map.Entry<String, String> file : files.entrySet()) {
        Path target = base.resolve(file.getKey()).normalize();
        if (!target.startsWith(base)) {
          throw new IllegalArgumentException("Generated path escapes the output directory: " + file.getKey());
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, file.getValue());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write generated project to " + root, e);
    }
  }
}
