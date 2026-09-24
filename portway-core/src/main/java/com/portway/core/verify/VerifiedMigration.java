package com.portway.core.verify;

import com.portway.core.MigrationResult;
import com.portway.core.MigrationSession;
import com.portway.core.generate.MigrationOptions;
import com.portway.core.generate.MigrationOptions.BodyOverride;
import com.portway.core.report.Finding;
import com.portway.core.report.FindingCode;
import com.portway.core.report.MethodReport;
import com.portway.core.report.Severity;
import com.portway.core.report.Strategy;
import com.portway.core.translate.BodyTranslator;
import com.portway.core.translate.Translation;
import com.portway.core.translate.TranslationRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generates, compiles, and repairs until the project compiles or nothing more can be done.
 *
 * <p>The model is never trusted, only checked. Methods the rules could not translate go to the
 * translator if there is one. Everything is then compiled. A method whose body fails gets one
 * repair attempt if a translator wrote it; otherwise, or if the repair fails too, it is demoted to
 * a stub. The project is regenerated and compiled again. The loop ends when the build is clean,
 * when a round changes nothing (the remaining errors are outside any method body), or after a
 * fixed number of rounds.
 */
public final class VerifiedMigration {

  /** Per-file compile status, as the review UI shows it. */
  public enum FileStatus {
    PASS,
    FAIL,
    NOT_RUN
  }

  /** Progress callbacks, so the pipeline can publish a stage per step. */
  public interface Listener {
    Listener NONE = new Listener() {};

    default void aiFill(int methods) {}

    default void verify(int round) {}

    default void repair(int round, int methods) {}
  }

  /**
   * @param compile the last compile run, or null when verification was skipped
   * @param findings the generation findings plus one COMPILE_ERROR per error that survived
   */
  public record Outcome(
      MigrationResult result,
      CompileResult compile,
      Map<String, FileStatus> fileStatus,
      int rounds,
      List<Finding> findings) {

    public boolean compiles() {
      return compile != null && compile.success();
    }
  }

  private static final int DEFAULT_MAX_ROUNDS = 3;
  private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+([\\w.]+);");

  private final CompileVerifier verifier;
  private final BodyTranslator translator;
  private final int maxRounds;

  /**
   * @param translator may be null, in which case rule failures are demoted straight to stubs
   */
  public VerifiedMigration(CompileVerifier verifier, BodyTranslator translator) {
    this(verifier, translator, DEFAULT_MAX_ROUNDS);
  }

  public VerifiedMigration(CompileVerifier verifier, BodyTranslator translator, int maxRounds) {
    this.verifier = verifier;
    this.translator = translator;
    this.maxRounds = maxRounds;
  }

  public Outcome run(MigrationSession session, Path workDir, Listener listener) {
    MigrationResult result = session.result() != null ? session.result() : session.generate();
    Map<String, String> aiBodies = new LinkedHashMap<>();
    Set<String> repaired = new HashSet<>();

    if (translator != null) {
      result = aiFill(session, result, aiBodies, listener);
    }
    if (verifier == null) {
      return new Outcome(result, null, statuses(result, null), 0, result.findings());
    }

    CompileResult compile;
    int round = 0;
    while (true) {
      round++;
      listener.verify(round);
      compile = compileInto(result, workDir.resolve("round-" + round));
      if (compile.success() || round > maxRounds) {
        break;
      }
      Map<String, List<CompileError>> byMethod = errorsByMethod(result, compile);
      listener.repair(round, byMethod.size());

      MigrationOptions options = session.options();
      boolean changed = false;
      for (Map.Entry<String, List<CompileError>> entry : byMethod.entrySet()) {
        MethodReport method = result.method(entry.getKey());
        String errors =
            entry.getValue().stream().map(e -> "line " + e.line() + ": " + e.message()).collect(Collectors.joining("\n"));
        Optional<Translation> fixed = Optional.empty();

        if (translator != null && !repaired.contains(method.key())) {
          repaired.add(method.key());
          TranslationRequest request = TranslationRequest.of(method, importsOf(result, method));
          if (method.strategy() == Strategy.AI_VERIFIED && aiBodies.containsKey(method.key())) {
            fixed = translator.repair(request, aiBodies.get(method.key()), errors);
            fixed.ifPresent(t -> aiBodies.put(method.key(), t.javaBody()));
          } else if (method.strategy() == Strategy.RULE_MAPPED) {
            // A rule produced code that does not compile. The model gets one
            // clean attempt at it before a human does.
            fixed = translator.translate(request);
            fixed.ifPresent(t -> aiBodies.put(method.key(), t.javaBody()));
          }
        }

        if (fixed.isPresent()) {
          Strategy strategy = method.strategy() == Strategy.AI_VERIFIED ? Strategy.AI_REPAIRED : Strategy.AI_VERIFIED;
          options = options.withOverride(
              method.key(), new BodyOverride(fixed.get().javaBody(), fixed.get().requiredImports(), strategy));
          changed = true;
        } else if (method.strategy() != Strategy.MANUAL_REQUIRED) {
          String who = method.strategy() == Strategy.RULE_MAPPED ? "the rule translation" : "the AI translation";
          options = options.withForcedManual(
              method.key(), who + " did not compile (" + entry.getValue().get(0).message().lines().findFirst().orElse("") + ")");
          changed = true;
        }
      }
      if (!changed) {
        // Everything left is outside a method body: an import, a field type, a
        // signature. Regenerating would change nothing.
        break;
      }
      session.options(options);
      result = session.generate();
    }

    List<Finding> findings = new ArrayList<>(result.findings());
    for (CompileError error : compile.errors()) {
      findings.add(
          new Finding(
              Severity.HIGH,
              FindingCode.COMPILE_ERROR,
              "Does not compile: " + error.message().lines().findFirst().orElse(error.message()),
              sourceOf(result, error.path()),
              0,
              error.path(),
              error.line()));
    }
    return new Outcome(result, compile, statuses(result, compile), round, findings);
  }

  private MigrationResult aiFill(
      MigrationSession session, MigrationResult result, Map<String, String> aiBodies, Listener listener) {
    MigrationOptions options = session.options();
    List<TranslationRequest> requests = new ArrayList<>();
    for (MethodReport method : result.methods()) {
      boolean eligible =
          method.aiEligible()
              && method.strategy() == Strategy.MANUAL_REQUIRED
              && !options.forcedManual().containsKey(method.key())
              && !options.bodyOverrides().containsKey(method.key());
      if (eligible) {
        requests.add(TranslationRequest.of(method, importsOf(result, method)));
      }
    }
    listener.aiFill(requests.size());
    if (requests.isEmpty()) {
      return result;
    }
    Map<String, Translation> translations = translator.translateAll(requests);
    for (Map.Entry<String, Translation> t : translations.entrySet()) {
      options = options.withOverride(
          t.getKey(),
          new BodyOverride(t.getValue().javaBody(), t.getValue().requiredImports(), Strategy.AI_VERIFIED));
      aiBodies.put(t.getKey(), t.getValue().javaBody());
    }
    session.options(options);
    return translations.isEmpty() ? result : session.generate();
  }

  private CompileResult compileInto(MigrationResult result, Path dir) {
    try {
      if (Files.exists(dir)) {
        InProcessCompileVerifier.deleteQuietly(dir);
      }
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    ProjectWriter.write(result.files(), dir);
    return verifier.verify(dir);
  }

  /** Compile errors grouped by the method they fall in. Errors outside any method are left out. */
  static Map<String, List<CompileError>> errorsByMethod(MigrationResult result, CompileResult compile) {
    Map<String, List<CompileError>> byMethod = new LinkedHashMap<>();
    Map<String, JavaSourceIndex> indexes = new LinkedHashMap<>();
    for (CompileError error : compile.errors()) {
      if (error.path() == null || !result.files().containsKey(error.path())) {
        continue;
      }
      JavaSourceIndex index =
          indexes.computeIfAbsent(error.path(), p -> JavaSourceIndex.of(result.files().get(p)));
      index
          .reportAt(error.line(), result.methodsIn(error.path()))
          .ifPresent(m -> byMethod.computeIfAbsent(m.key(), k -> new ArrayList<>()).add(error));
    }
    return byMethod;
  }

  private static List<String> importsOf(MigrationResult result, MethodReport method) {
    String content = result.files().get(method.generatedPath());
    List<String> imports = new ArrayList<>();
    if (content != null) {
      Matcher m = IMPORT.matcher(content);
      while (m.find()) {
        imports.add(m.group(1));
      }
    }
    return imports;
  }

  private static String sourceOf(MigrationResult result, String generatedPath) {
    return result.generated().stream()
        .filter(f -> f.path().equals(generatedPath))
        .map(f -> f.sourcePath())
        .findFirst()
        .orElse(null);
  }

  private static Map<String, FileStatus> statuses(MigrationResult result, CompileResult compile) {
    Map<String, FileStatus> statuses = new LinkedHashMap<>();
    for (String path : result.files().keySet()) {
      if (!path.endsWith(".java")) {
        continue;
      }
      FileStatus status =
          compile == null ? FileStatus.NOT_RUN : compile.errorsIn(path).isEmpty() ? FileStatus.PASS : FileStatus.FAIL;
      statuses.put(path, status);
    }
    return statuses;
  }
}
