package com.portway.app.cli;

import com.portway.app.verify.DockerCompileVerifier;
import com.portway.app.verify.VerifyClasspath;
import com.portway.core.MigrationResult;
import com.portway.core.MigrationSession;
import com.portway.core.generate.MigrationOptions;
import com.portway.core.report.Finding;
import com.portway.core.report.MethodReport;
import com.portway.core.report.Severity;
import com.portway.core.report.Strategy;
import com.portway.core.translate.BodyTranslator;
import com.portway.core.verify.CompileVerifier;
import com.portway.core.verify.InProcessCompileVerifier;
import com.portway.core.verify.ProjectWriter;
import com.portway.core.verify.VerifiedMigration;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * {@code java -jar portway.jar migrate ./sample-dotnet -o ./out}: the whole pipeline without the web
 * application.
 *
 * <p>Runs before Spring starts, so the CLI needs no database and no configuration. The exit code is
 * 0 when the generated project compiles (or verification was skipped), 2 when it does not, and 1 for
 * a usage error.
 */
public final class PortwayCli {

  static final String USAGE =
      """
      Usage: java -jar portway.jar migrate <source-dir> [options]

        -o, --out <dir>             where to write the Spring Boot project (default: ./migrated)
        --package <name>            base Java package (default: from the C# root namespace)
        --keep-interface-prefix     keep IBookService instead of BookService/BookServiceImpl
        --verifier <in-process|docker|none>
                                    how to compile-check the output (default: in-process)
        --ai                        translate methods the rules cannot, using the LLM configured
                                    by ANTHROPIC_API_KEY (and PORTWAY_AI_MODEL)
      """;

  private final PrintStream out;
  private final PrintStream err;
  private final Supplier<BodyTranslator> translatorFactory;

  public PortwayCli(PrintStream out, PrintStream err, Supplier<BodyTranslator> translatorFactory) {
    this.out = out;
    this.err = err;
    this.translatorFactory = translatorFactory;
  }

  public int run(String... args) {
    Arguments a;
    try {
      a = Arguments.parse(args);
    } catch (IllegalArgumentException e) {
      err.println(e.getMessage());
      err.print(USAGE);
      return 1;
    }
    if (!Files.isDirectory(a.source)) {
      err.println("Not a directory: " + a.source);
      return 1;
    }

    MigrationOptions options =
        MigrationOptions.defaults().withBasePackage(a.basePackage).withKeepInterfacePrefix(a.keepInterfacePrefix);
    MigrationSession session = new MigrationSession(a.source, options);
    out.println("Migrating " + a.source.toAbsolutePath().normalize());
    session.run();

    CompileVerifier verifier =
        switch (a.verifier) {
          case "none" -> null;
          case "docker" ->
              new DockerCompileVerifier(
                  Path.of(System.getProperty("user.home"), ".portway", "m2"), Duration.ofSeconds(180), 1024L * 1024 * 1024);
          default -> new InProcessCompileVerifier(VerifyClasspath.resolve());
        };
    BodyTranslator translator = a.ai ? translatorFactory.get() : null;

    Path work = tempDir();
    VerifiedMigration.Outcome outcome;
    try {
      outcome =
          new VerifiedMigration(verifier, translator)
              .run(
                  session,
                  work,
                  new VerifiedMigration.Listener() {
                    @Override
                    public void aiFill(int methods) {
                      out.println("  AI translation: " + methods + " method(s)");
                    }

                    @Override
                    public void verify(int round) {
                      out.println("  Compiling (" + verifier.name() + "), round " + round);
                    }

                    @Override
                    public void repair(int round, int methods) {
                      out.println("  Repairing " + methods + " method(s) that did not compile");
                    }
                  });
    } finally {
      deleteQuietly(work);
    }

    MigrationResult result = outcome.result();
    ProjectWriter.write(result.files(), a.out);
    summarise(outcome, a.out);
    return verifier == null || outcome.compiles() ? 0 : 2;
  }

  private void summarise(VerifiedMigration.Outcome outcome, Path outDir) {
    MigrationResult result = outcome.result();
    Map<Strategy, Integer> byStrategy = new EnumMap<>(Strategy.class);
    for (MethodReport m : result.methods()) {
      byStrategy.merge(m.strategy(), 1, Integer::sum);
    }
    Map<Severity, Integer> bySeverity = new EnumMap<>(Severity.class);
    for (Finding f : outcome.findings()) {
      bySeverity.merge(f.severity(), 1, Integer::sum);
    }

    out.println();
    out.println("Files generated:   " + result.files().size());
    out.printf(
        "Methods:           %d total, %d by rules, %d by AI, %d stubbed for manual work%n",
        result.methods().size(),
        byStrategy.getOrDefault(Strategy.RULE_MAPPED, 0),
        byStrategy.getOrDefault(Strategy.AI_VERIFIED, 0) + byStrategy.getOrDefault(Strategy.AI_REPAIRED, 0),
        byStrategy.getOrDefault(Strategy.MANUAL_REQUIRED, 0));
    if (outcome.compile() == null) {
      out.println("Compile check:     skipped");
    } else {
      out.println(
          "Compile check:     "
              + (outcome.compiles() ? "PASS" : "FAIL (" + outcome.compile().errors().size() + " errors)")
              + " — " + outcome.compile().verifier());
    }
    out.printf(
        "Findings:          %d high, %d medium, %d low, %d info%n",
        bySeverity.getOrDefault(Severity.HIGH, 0),
        bySeverity.getOrDefault(Severity.MEDIUM, 0),
        bySeverity.getOrDefault(Severity.LOW, 0),
        bySeverity.getOrDefault(Severity.INFO, 0));
    out.println("Output:            " + outDir.toAbsolutePath().normalize());
    out.println("Review:            " + outDir.resolve("MIGRATION-NOTES.md").normalize());
  }

  /** Parsed command line. */
  record Arguments(
      Path source, Path out, String basePackage, boolean keepInterfacePrefix, String verifier, boolean ai) {

    static Arguments parse(String... args) {
      if (args.length < 2 || !args[0].equals("migrate")) {
        throw new IllegalArgumentException("Expected: migrate <source-dir>");
      }
      Path source = Path.of(args[1]);
      Path out = Path.of("migrated");
      String basePackage = null;
      boolean keep = false;
      String verifier = "in-process";
      boolean ai = false;
      for (int i = 2; i < args.length; i++) {
        switch (args[i]) {
          case "-o", "--out" -> out = Path.of(value(args, ++i, args[i - 1]));
          case "--package" -> basePackage = value(args, ++i, "--package");
          case "--keep-interface-prefix" -> keep = true;
          case "--verifier" -> {
            verifier = value(args, ++i, "--verifier");
            if (!verifier.equals("in-process") && !verifier.equals("docker") && !verifier.equals("none")) {
              throw new IllegalArgumentException("Unknown verifier: " + verifier);
            }
          }
          case "--ai" -> ai = true;
          default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
        }
      }
      return new Arguments(source, out, basePackage, keep, verifier, ai);
    }

    private static String value(String[] args, int i, String option) {
      if (i >= args.length) {
        throw new IllegalArgumentException(option + " needs a value");
      }
      return args[i];
    }
  }

  private static Path tempDir() {
    try {
      return Files.createTempDirectory("portway-verify");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteQuietly(Path dir) {
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted((x, y) -> y.getNameCount() - x.getNameCount()).forEach(p -> p.toFile().delete());
    } catch (IOException ignored) {
      // temporary files only
    }
  }
}
