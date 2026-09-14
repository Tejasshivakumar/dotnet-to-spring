package com.portway.core.generate;

import static org.assertj.core.api.Assertions.assertThat;

import com.portway.core.DefaultMigrator;
import com.portway.core.MigrationResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiles what the migrator produced.
 *
 * <p>Generated code that merely looks correct is worth very little. This is a thin, in-process
 * version of the compile verification the pipeline does properly in week 3, and it exists now
 * because "Book.cs produces a correct Book.java" is not a claim that can be made by reading.
 */
class GeneratedOutputCompilesTest {

  @Test
  void generatedProjectCompiles(@TempDir Path workDir) throws IOException {
    String classpath = springClasspath();

    MigrationResult result = new DefaultMigrator().migrate(sampleRoot());
    assertThat(result.files()).isNotEmpty();

    List<Path> sources = new ArrayList<>();
    for (Map.Entry<String, String> file : result.files().entrySet()) {
      if (!file.getKey().endsWith(".java")) {
        continue;
      }
      Path target = workDir.resolve(file.getKey());
      Files.createDirectories(target.getParent());
      Files.writeString(target, file.getValue());
      sources.add(target);
    }

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).describedAs("tests must run on a JDK, not a JRE").isNotNull();

    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    Path classes = workDir.resolve("classes");
    Files.createDirectories(classes);

    try (StandardJavaFileManager files =
        compiler.getStandardFileManager(diagnostics, null, null)) {
      boolean ok =
          compiler
              .getTask(
                  null,
                  files,
                  diagnostics,
                  List.of("-classpath", classpath, "-d", classes.toString(), "-proc:none"),
                  null,
                  files.getJavaFileObjectsFromPaths(sources))
              .call();

      String errors =
          diagnostics.getDiagnostics().stream()
              .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)
              .map(d -> d.getSource() + ":" + d.getLineNumber() + " " + d.getMessage(null))
              .reduce("", (a, b) -> a + "\n" + b);

      assertThat(ok).describedAs("generated code did not compile:%s", errors).isTrue();
    }
  }

  /**
   * The test classpath itself, which carries the JPA, validation and Spring Data APIs the generated
   * code imports. Using it directly means this check needs no setup step and cannot silently skip.
   */
  private static String springClasspath() {
    return System.getProperty("java.class.path");
  }

  private static Path sampleRoot() {
    return repoRoot().resolve("sample-dotnet/BookstoreApi");
  }

  private static Path repoRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 5 && dir != null; i++) {
      if (Files.isDirectory(dir.resolve("sample-dotnet"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("Cannot find repo root");
  }
}
