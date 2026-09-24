package com.portway.app.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import com.portway.app.TestPaths;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The CLI end to end: sample in, compiled Spring Boot project out, no Spring context started. */
class PortwayCliTest {

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
  private final PortwayCli cli =
      new PortwayCli(
          new PrintStream(out, true, StandardCharsets.UTF_8),
          new PrintStream(err, true, StandardCharsets.UTF_8),
          () -> {
            throw new AssertionError("no AI in this test");
          });

  @Test
  void migratesAndVerifiesTheSample(@TempDir Path target) {
    int exit = cli.run("migrate", sample().toString(), "-o", target.toString());

    assertThat(exit).describedAs(err.toString()).isZero();
    assertThat(target.resolve("pom.xml")).exists();
    assertThat(target.resolve("src/main/java/bookstoreapi/service/BookServiceImpl.java")).exists();
    assertThat(target.resolve("MIGRATION-NOTES.md")).exists();
    assertThat(out.toString())
        .contains("16 total, 14 by rules, 0 by AI, 2 stubbed")
        .contains("Compile check:     PASS");
  }

  @Test
  void honoursPackageAndInterfacePrefixOptions(@TempDir Path target) {
    int exit =
        cli.run(
            "migrate", sample().toString(), "-o", target.toString(),
            "--package", "com.example.books", "--keep-interface-prefix", "--verifier", "none");

    assertThat(exit).isZero();
    assertThat(target.resolve("src/main/java/com/example/books/service/IBookService.java")).exists();
    assertThat(target.resolve("src/main/java/com/example/books/service/BookService.java")).exists();
    assertThat(out.toString()).contains("Compile check:     skipped");
  }

  @Test
  void rejectsBadUsageWithExitCodeOne() {
    assertThat(cli.run("migrate")).isEqualTo(1);
    assertThat(cli.run("migrate", "x", "--verifier", "gradle")).isEqualTo(1);
    assertThat(err.toString()).contains("Usage:");
  }

  @Test
  void rejectsMissingSourceDirectory() {
    assertThat(cli.run("migrate", "/definitely/not/here")).isEqualTo(1);
    assertThat(err.toString()).contains("Not a directory");
  }

  private static Path sample() {
    return TestPaths.sample();
  }
}
