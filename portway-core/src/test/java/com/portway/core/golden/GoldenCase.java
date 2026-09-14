package com.portway.core.golden;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One directory under {@code testdata/}: C# in {@code input/}, the Java project we expect out of it
 * in {@code expected/}.
 *
 * @param name the directory name, used as the JUnit display name
 */
public record GoldenCase(String name, Path dir) {

  public Path input() {
    return dir.resolve("input");
  }

  public Path expected() {
    return dir.resolve("expected");
  }

  /** Expected file contents keyed by path relative to {@code expected/}, forward slashes. */
  public Map<String, String> expectedFiles() {
    Path root = expected();
    if (!Files.isDirectory(root)) {
      return Map.of();
    }
    try (Stream<Path> walk = Files.walk(root)) {
      Map<String, String> files = new LinkedHashMap<>();
      walk.filter(Files::isRegularFile)
          .filter(p -> !p.getFileName().toString().equals(".gitkeep"))
          .sorted(Comparator.comparing(Path::toString))
          .forEach(p -> files.put(relative(root, p), read(p)));
      return files;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read expected files for case " + name, e);
    }
  }

  static String relative(Path root, Path file) {
    return root.relativize(file).toString().replace('\\', '/');
  }

  static String read(Path p) {
    try {
      return Files.readString(p);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + p, e);
    }
  }

  @Override
  public String toString() {
    return name;
  }
}
