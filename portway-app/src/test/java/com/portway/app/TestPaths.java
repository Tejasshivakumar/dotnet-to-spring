package com.portway.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Locates repository fixtures from whichever directory the tests run in. */
public final class TestPaths {

  private TestPaths() {}

  public static Path sample() {
    Path dir = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 5 && dir != null; i++) {
      if (Files.isDirectory(dir.resolve("sample-dotnet"))) {
        return dir.resolve("sample-dotnet/BookstoreApi");
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("Cannot find sample-dotnet");
  }
}
