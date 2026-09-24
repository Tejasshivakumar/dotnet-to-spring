package com.portway.core.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.portway.core.MigrationSession;
import com.portway.core.generate.MigrationOptions;
import com.portway.core.report.FindingCode;
import com.portway.core.report.MethodReport;
import com.portway.core.report.Strategy;
import com.portway.core.translate.BodyTranslator;
import com.portway.core.translate.Translation;
import com.portway.core.translate.TranslationRequest;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The verify-and-repair loop, driven by the real in-process compiler and scripted translators.
 *
 * <p>The translator is faked because the loop's job is what happens <em>after</em> a translation:
 * whether it is kept, repaired, or demoted. Those decisions depend only on the compiler.
 */
class VerifiedMigrationTest {

  private static final String TOTAL_VALUE = "BookService.TotalCatalogueValue()";

  private static final String CORRECT_TOTAL =
      """
      return bookRepository.findAll().stream()
          .map(b -> b.getPrice().multiply(BigDecimal.valueOf(b.getStockCount())))
          .reduce(BigDecimal.ZERO, BigDecimal::add);
      """;

  private final CompileVerifier compiler = new InProcessCompileVerifier(testClasspath());

  @Test
  void sampleCompilesWithoutATranslatorAndStubsTheRest(@TempDir Path work) {
    VerifiedMigration.Outcome outcome = run(sample(), null, work);

    assertThat(outcome.compiles()).isTrue();
    assertThat(outcome.rounds()).isEqualTo(1);
    assertThat(outcome.fileStatus()).isNotEmpty().allSatisfy((path, status) ->
        assertThat(status).isEqualTo(VerifiedMigration.FileStatus.PASS));
    assertThat(strategy(outcome, TOTAL_VALUE)).isEqualTo(Strategy.MANUAL_REQUIRED);
  }

  @Test
  void translationThatCompilesIsAiVerified(@TempDir Path work) {
    Scripted translator = new Scripted(r -> CORRECT_TOTAL, null);

    VerifiedMigration.Outcome outcome = run(sample(), translator, work);

    assertThat(outcome.compiles()).isTrue();
    assertThat(strategy(outcome, TOTAL_VALUE)).isEqualTo(Strategy.AI_VERIFIED);
    // Tier C is never offered to the model.
    assertThat(translator.asked).containsExactly(TOTAL_VALUE);
    assertThat(outcome.result().files().get("src/main/java/bookstoreapi/service/BookServiceImpl.java"))
        .contains("reduce(BigDecimal.ZERO, BigDecimal::add)");
  }

  @Test
  void translationFixedByOneRepairIsAiRepaired(@TempDir Path work) {
    Scripted translator = new Scripted(r -> "return total;", r -> CORRECT_TOTAL);

    VerifiedMigration.Outcome outcome = run(sample(), translator, work);

    assertThat(outcome.compiles()).isTrue();
    assertThat(outcome.rounds()).isEqualTo(2);
    assertThat(strategy(outcome, TOTAL_VALUE)).isEqualTo(Strategy.AI_REPAIRED);
    assertThat(translator.repairErrors.get(0)).contains("cannot find symbol");
  }

  @Test
  void translationThatFailsTwiceIsDemoted(@TempDir Path work) {
    Scripted translator = new Scripted(r -> "return total;", r -> "return stillMissing;");

    VerifiedMigration.Outcome outcome = run(sample(), translator, work);

    assertThat(outcome.compiles()).isTrue();
    MethodReport method = outcome.result().method(TOTAL_VALUE);
    assertThat(method.strategy()).isEqualTo(Strategy.MANUAL_REQUIRED);
    assertThat(method.reason()).contains("AI translation did not compile");
    assertThat(translator.repairErrors).hasSize(1);
  }

  @Test
  void ruleTranslationThatFailsToCompileIsDemoted(@TempDir Path input, @TempDir Path work) throws IOException {
    // Math.Round(x, 2) passes every token rule and becomes Math.round(x, 2),
    // which does not exist. Only the compiler can know.
    Files.writeString(
        input.resolve("PriceService.cs"),
        """
        namespace Shop;

        public class PriceService
        {
            public double Rounded(double x)
            {
                return Math.Round(x, 2);
            }

            public double Doubled(double x)
            {
                return x * 2;
            }
        }
        """);

    VerifiedMigration.Outcome outcome = run(input, null, work);

    assertThat(outcome.compiles()).isTrue();
    assertThat(outcome.rounds()).isEqualTo(2);
    MethodReport rounded = outcome.result().method("PriceService.Rounded(double)");
    assertThat(rounded.strategy()).isEqualTo(Strategy.MANUAL_REQUIRED);
    assertThat(rounded.reason()).contains("rule translation did not compile");
    assertThat(strategy(outcome, "PriceService.Doubled(double)")).isEqualTo(Strategy.RULE_MAPPED);
    assertThat(outcome.findings()).noneMatch(f -> f.code() == FindingCode.COMPILE_ERROR);
  }

  @Test
  void errorOutsideAnyMethodIsReportedNotLooped(@TempDir Path input, @TempDir Path work) throws IOException {
    // A property of an unknown external type: the field itself cannot compile, and
    // no amount of body repair fixes a field.
    Files.writeString(
        input.resolve("WidgetDto.cs"),
        """
        namespace Shop;

        public class WidgetDto
        {
            public ExternalThing Thing { get; set; }
        }
        """);

    VerifiedMigration.Outcome outcome = run(input, null, work);

    assertThat(outcome.compiles()).isFalse();
    assertThat(outcome.rounds()).isEqualTo(1);
    assertThat(outcome.fileStatus()).containsEntry("src/main/java/shop/dto/WidgetDto.java", VerifiedMigration.FileStatus.FAIL);
    assertThat(outcome.findings())
        .anySatisfy(f -> {
          assertThat(f.code()).isEqualTo(FindingCode.COMPILE_ERROR);
          assertThat(f.generatedPath()).isEqualTo("src/main/java/shop/dto/WidgetDto.java");
        });
  }

  // ------------------------------------------------------------------ helpers

  private VerifiedMigration.Outcome run(Path input, BodyTranslator translator, Path work) {
    MigrationSession session = new MigrationSession(input, MigrationOptions.defaults());
    session.run();
    return new VerifiedMigration(compiler, translator).run(session, work, VerifiedMigration.Listener.NONE);
  }

  private static Strategy strategy(VerifiedMigration.Outcome outcome, String key) {
    return outcome.result().method(key).strategy();
  }

  /** A translator whose answers are scripted per call type. */
  private static final class Scripted implements BodyTranslator {
    private final Function<TranslationRequest, String> first;
    private final Function<TranslationRequest, String> repair;
    final List<String> asked = new ArrayList<>();
    final List<String> repairErrors = new ArrayList<>();

    Scripted(Function<TranslationRequest, String> first, Function<TranslationRequest, String> repair) {
      this.first = first;
      this.repair = repair;
    }

    @Override
    public Optional<Translation> translate(TranslationRequest request) {
      asked.add(request.methodKey());
      return Optional.of(new Translation(first.apply(request), List.of("java.math.BigDecimal"), "", 0.8));
    }

    @Override
    public Optional<Translation> repair(TranslationRequest request, String previousBody, String compilerErrors) {
      repairErrors.add(compilerErrors);
      return repair == null
          ? Optional.empty()
          : Optional.of(new Translation(repair.apply(request), List.of("java.math.BigDecimal"), "", 0.6));
    }
  }

  static List<Path> testClasspath() {
    return java.util.Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
        .map(Path::of)
        .toList();
  }

  private static Path sample() {
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
