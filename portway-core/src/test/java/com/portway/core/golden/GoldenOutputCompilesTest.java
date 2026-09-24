package com.portway.core.golden;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every golden expectation must compile.
 *
 * <p>The golden test pins what the rules produce; this one pins that what they produce is Java.
 * Without it, a rule can drift into emitting plausible code that does not compile, and a
 * {@code -Dupdate.golden=true} run would bless it. In production the compile verifier would catch
 * the same code and demote the method to a stub; this test catches it at the rule instead.
 */
class GoldenOutputCompilesTest {

  static List<GoldenCase> goldenCases() {
    return GoldenCases.all();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("goldenCases")
  void expectedJavaCompiles(GoldenCase goldenCase, @TempDir Path classes) throws IOException {
    List<Path> sources;
    try (Stream<Path> walk = Files.walk(goldenCase.expected())) {
      sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
    }
    assertThat(sources).isNotEmpty();

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
      boolean ok =
          compiler
              .getTask(
                  null,
                  files,
                  diagnostics,
                  List.of(
                      "-classpath", System.getProperty("java.class.path"),
                      "-d", classes.toString(),
                      "-proc:none"),
                  null,
                  files.getJavaFileObjectsFromPaths(sources))
              .call();
      String errors =
          diagnostics.getDiagnostics().stream()
              .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
              .map(d -> goldenCase.expected().relativize(Path.of(d.getSource().toUri()))
                  + ":" + d.getLineNumber() + " " + d.getMessage(null))
              .reduce("", (a, b) -> a + "\n" + b);
      assertThat(ok).describedAs("%s does not compile:%s", goldenCase, errors).isTrue();
    }
  }
}
