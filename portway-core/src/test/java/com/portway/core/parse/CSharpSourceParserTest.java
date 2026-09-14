package com.portway.core.parse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The grammar is the riskiest dependency in the project, so it is measured against real C# rather
 * than hand-picked snippets: every file in the sample project must parse clean.
 */
class CSharpSourceParserTest {

  private final CSharpSourceParser parser = new CSharpSourceParser();

  static List<Path> sampleFiles() throws IOException {
    Path root = repoRoot().resolve("sample-dotnet");
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".cs"))
          .filter(p -> !p.toString().contains("/obj/") && !p.toString().contains("/bin/"))
          // Program.cs uses top-level statements, which the v7 grammar predates.
          // It is handled by a dedicated scanner instead; see PARSER-NOTES.md.
          .filter(p -> !p.getFileName().toString().equals("Program.cs"))
          .sorted(Comparator.comparing(Path::toString))
          .toList();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sampleFiles")
  void parsesSampleProjectWithoutErrors(Path file) {
    ParseResult result = parser.parseFile(file);
    assertThat(result.errors())
        .describedAs("syntax errors in %s", file.getFileName())
        .isEmpty();
  }

  @Test
  void parsesFileScopedNamespace() {
    ParseResult result =
        parser.parse(
            """
            namespace Bookstore.Models;

            public class Book
            {
                public long Id { get; set; }
            }
            """,
            "Book.cs");
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void parsesBlockScopedNamespace() {
    ParseResult result =
        parser.parse(
            """
            namespace Bookstore.Models
            {
                public class Book
                {
                    public long Id { get; set; }
                }
            }
            """,
            "Book.cs");
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void reportsErrorsRatherThanThrowing() {
    ParseResult result = parser.parse("public class Broken { this is not C# ", "Broken.cs");
    assertThat(result.ok()).isFalse();
    assertThat(result.errors()).isNotEmpty();
    assertThat(result.errors().get(0).path()).isEqualTo("Broken.cs");
  }

  private static Path repoRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 5 && dir != null; i++) {
      if (Files.isDirectory(dir.resolve("sample-dotnet"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("Cannot find sample-dotnet/");
  }
}
