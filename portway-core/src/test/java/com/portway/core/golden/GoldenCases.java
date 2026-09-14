package com.portway.core.golden;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Locates the {@code testdata/} directory and enumerates the cases inside it. */
public final class GoldenCases {

  /**
   * Set {@code -Dupdate.golden=true} to overwrite every {@code expected/} directory with whatever
   * the migrator currently produces.
   *
   * <p>This exists because expectations get regenerated constantly while the rule engine is being
   * built, and regenerating by hand quietly discourages adding cases. Always read the resulting
   * diff before committing it: the flag makes it trivial to bless a regression.
   */
  public static final String UPDATE_PROPERTY = "update.golden";

  private GoldenCases() {}

  public static boolean updateMode() {
    return Boolean.getBoolean(UPDATE_PROPERTY);
  }

  /** Every case directory, sorted by name so numbering controls execution order. */
  public static List<GoldenCase> all() {
    Path root = testdataRoot();
    try (Stream<Path> dirs = Files.list(root)) {
      return dirs.filter(Files::isDirectory)
          .filter(d -> Files.isDirectory(d.resolve("input")))
          .sorted(Comparator.comparing(p -> p.getFileName().toString()))
          .map(d -> new GoldenCase(d.getFileName().toString(), d))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot list golden cases under " + root, e);
    }
  }

  /**
   * Walks up from the working directory looking for {@code testdata/}, so the tests run the same
   * from the module directory, the repo root, or an IDE with either as its working directory.
   */
  public static Path testdataRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 5 && dir != null; i++) {
      Path candidate = dir.resolve("testdata");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException(
        "Cannot find testdata/ from " + Paths.get("").toAbsolutePath());
  }
}
