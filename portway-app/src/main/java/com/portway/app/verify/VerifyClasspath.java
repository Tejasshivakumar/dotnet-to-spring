package com.portway.app.verify;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The classpath the in-process verifier compiles generated code against.
 *
 * <p>The jars ship inside the application as resources under {@code verify-lib/}. Run from an
 * exploded build they are already files; run from the packaged jar they are extracted once into a
 * temporary directory, because javac reads a classpath from the filesystem.
 */
public final class VerifyClasspath {

  private static final String INDEX = "verify-lib.txt";
  private static volatile List<Path> cached;

  private VerifyClasspath() {}

  public static List<Path> resolve() {
    List<Path> jars = cached;
    if (jars == null) {
      synchronized (VerifyClasspath.class) {
        if (cached == null) {
          cached = load();
        }
        jars = cached;
      }
    }
    return jars;
  }

  /** The jar names the index lists. */
  public static List<String> listed() {
    ClassLoader loader = VerifyClasspath.class.getClassLoader();
    try (InputStream in = loader.getResourceAsStream(INDEX)) {
      if (in == null) {
        throw new IllegalStateException("Missing " + INDEX + " on the classpath");
      }
      List<String> names = new ArrayList<>();
      new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
          .lines()
          .map(String::strip)
          .filter(l -> !l.isEmpty() && !l.startsWith("#"))
          .forEach(names::add);
      return names;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<Path> load() {
    ClassLoader loader = VerifyClasspath.class.getClassLoader();
    List<Path> jars = new ArrayList<>();
    Path extracted = null;
    for (String name : listed()) {
      URL url = loader.getResource("verify-lib/" + name);
      if (url == null) {
        throw new IllegalStateException("verify-lib/" + name + " is listed but not packaged");
      }
      if ("file".equals(url.getProtocol())) {
        try {
          jars.add(Path.of(url.toURI()));
          continue;
        } catch (URISyntaxException e) {
          throw new IllegalStateException(e);
        }
      }
      try {
        if (extracted == null) {
          extracted = Files.createTempDirectory("portway-verify-lib");
          extracted.toFile().deleteOnExit();
        }
        Path target = extracted.resolve(name);
        try (InputStream in = url.openStream()) {
          Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        target.toFile().deleteOnExit();
        jars.add(target);
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot extract " + name, e);
      }
    }
    return List.copyOf(jars);
  }
}
