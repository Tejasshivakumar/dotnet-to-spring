package com.portway.core.golden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compares a migration result against a case's {@code expected/} tree, file by file. */
public final class GoldenFileAssert {

  private GoldenFileAssert() {}

  /**
   * Asserts that {@code actual} matches the case's expectations exactly: same set of paths, same
   * content, ignoring only trailing whitespace at end of file.
   *
   * <p>Reports every mismatched path in one failure rather than stopping at the first, because
   * fixing a rule usually moves several files at once.
   */
  public static void matchesGolden(Map<String, String> actual, GoldenCase goldenCase) {
    if (GoldenCases.updateMode()) {
      rewrite(actual, goldenCase);
      return;
    }

    Map<String, String> expected = goldenCase.expectedFiles();

    Set<String> expectedPaths = new LinkedHashSet<>(expected.keySet());
    Set<String> actualPaths = new LinkedHashSet<>(actual.keySet());

    List<String> missing = new ArrayList<>(expectedPaths);
    missing.removeAll(actualPaths);
    List<String> unexpected = new ArrayList<>(actualPaths);
    unexpected.removeAll(expectedPaths);

    if (!missing.isEmpty() || !unexpected.isEmpty()) {
      fail(
          "Generated file set does not match case %s%n  missing:    %s%n  unexpected: %s"
              .formatted(goldenCase.name(), missing, unexpected));
    }

    List<String> differing = new ArrayList<>();
    for (String path : expectedPaths) {
      if (!normalise(expected.get(path)).equals(normalise(actual.get(path)))) {
        differing.add(path);
      }
    }

    if (differing.size() == 1) {
      String path = differing.get(0);
      assertThat(normalise(actual.get(path)))
          .describedAs("%s / %s", goldenCase.name(), path)
          .isEqualTo(normalise(expected.get(path)));
    } else if (!differing.isEmpty()) {
      fail(
          "%d files differ from golden in case %s: %s%nRe-run with -D%s=true to inspect the diff."
              .formatted(
                  differing.size(), goldenCase.name(), differing, GoldenCases.UPDATE_PROPERTY));
    }
  }

  private static String normalise(String s) {
    return s == null ? "" : s.replace("\r\n", "\n").stripTrailing();
  }

  private static void rewrite(Map<String, String> actual, GoldenCase goldenCase) {
    Path root = goldenCase.expected();
    // A migrator that returned nothing is a bug, not a new expectation. Without
    // this guard one broken run plus -Dupdate.golden=true silently deletes every
    // expectation in the case and the suite goes green on an empty contract.
    if (actual.isEmpty()) {
      fail(
          "Refusing to rewrite golden files for %s: the migrator produced no output."
              .formatted(goldenCase.name()));
    }
    try {
      if (Files.isDirectory(root)) {
        try (var walk = Files.walk(root)) {
          walk.sorted(java.util.Comparator.reverseOrder()).forEach(GoldenFileAssert::delete);
        }
      }
      for (Map.Entry<String, String> e : actual.entrySet()) {
        Path target = root.resolve(e.getKey());
        Files.createDirectories(target.getParent());
        Files.writeString(target, e.getValue());
      }
      System.out.printf("Rewrote golden expectations for %s (%d files)%n",
          goldenCase.name(), actual.size());
    } catch (IOException ex) {
      throw new UncheckedIOException("Cannot rewrite golden files for " + goldenCase.name(), ex);
    }
  }

  private static void delete(Path p) {
    try {
      Files.delete(p);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot delete " + p, e);
    }
  }
}
