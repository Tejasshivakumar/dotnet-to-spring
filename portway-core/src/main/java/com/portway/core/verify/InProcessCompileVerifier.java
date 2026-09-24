package com.portway.core.verify;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles generated sources with the JDK's own compiler, against a fixed classpath of the APIs
 * generated code may import.
 *
 * <p>Fast and needs no Docker, which is why the cloud deployment uses it. It is also weaker than a
 * real build: it cannot catch a dependency missing from the generated pom, or anything an
 * annotation processor would. The name says so, and the UI repeats it.
 */
public class InProcessCompileVerifier implements CompileVerifier {

  private final List<Path> classpath;

  public InProcessCompileVerifier(List<Path> classpath) {
    this.classpath = List.copyOf(classpath);
  }

  @Override
  public String name() {
    return "in-process (limited classpath)";
  }

  @Override
  public CompileResult verify(Path projectDir) {
    long started = System.nanoTime();
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("In-process verification needs a JDK, not a JRE");
    }
    List<Path> sources = javaSources(projectDir.resolve("src/main/java"));
    if (sources.isEmpty()) {
      return new CompileResult(true, List.of(), "No Java sources.", elapsed(started), name());
    }

    Path classes;
    try {
      classes = Files.createTempDirectory("portway-classes");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    StringWriter output = new StringWriter();
    boolean ok;
    try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
      List<String> options =
          List.of(
              "-classpath",
              classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)),
              "-d",
              classes.toString(),
              "-proc:none",
              "-Xmaxerrs",
              "500");
      ok =
          compiler
              .getTask(output, files, diagnostics, options, null, files.getJavaFileObjectsFromPaths(sources))
              .call();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      deleteQuietly(classes);
    }

    List<CompileError> errors = new ArrayList<>();
    StringBuilder log = new StringBuilder(output.toString());
    for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
      if (d.getKind() != Diagnostic.Kind.ERROR) {
        continue;
      }
      String path = null;
      if (d.getSource() != null) {
        path = projectDir.toAbsolutePath().relativize(Path.of(d.getSource().toUri()).toAbsolutePath())
            .toString().replace('\\', '/');
      }
      CompileError error =
          new CompileError(path, (int) Math.max(0, d.getLineNumber()), (int) Math.max(0, d.getColumnNumber()),
              d.getMessage(null));
      errors.add(error);
      log.append(error).append('\n');
    }
    return new CompileResult(ok && errors.isEmpty(), errors, log.toString(), elapsed(started), name());
  }

  private static List<Path> javaSources(Path root) {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Duration elapsed(long started) {
    return Duration.ofNanos(System.nanoTime() - started);
  }

  static void deleteQuietly(Path dir) {
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> p.toFile().delete());
    } catch (IOException ignored) {
      // Temporary output; nothing depends on its removal.
    }
  }
}
