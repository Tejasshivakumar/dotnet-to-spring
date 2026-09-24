package com.portway.core.generate;

import com.portway.core.report.Strategy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Knobs for a migration run.
 *
 * @param basePackage root Java package for generated code; when null it is derived from the C#
 *     root namespace, lowercased and sanitised
 * @param keepInterfacePrefix keep C#'s {@code IBookService} naming instead of renaming to
 *     {@code BookService} / {@code BookServiceImpl}. Java convention is to rename, so this
 *     defaults to false, but the C# names are sometimes worth keeping during a phased migration.
 * @param bodyOverrides method key to a Java body produced outside the rule engine, normally by the
 *     LLM. Keys are {@link com.portway.core.report.MethodReport#key()}.
 * @param forcedManual method key to the reason it must be stubbed, used to demote a method whose
 *     generated body failed to compile
 */
public record MigrationOptions(
    String basePackage,
    boolean keepInterfacePrefix,
    Map<String, BodyOverride> bodyOverrides,
    Map<String, String> forcedManual) {

  /**
   * A method body supplied from outside the rules.
   *
   * @param javaBody Java statements without the enclosing braces
   * @param imports qualified names the body uses by simple name
   * @param strategy how it was produced, which sets its confidence
   */
  public record BodyOverride(String javaBody, List<String> imports, Strategy strategy) {
    public BodyOverride {
      imports = List.copyOf(imports == null ? List.of() : imports);
    }
  }

  public MigrationOptions {
    bodyOverrides =
        Collections.unmodifiableMap(new LinkedHashMap<>(bodyOverrides == null ? Map.of() : bodyOverrides));
    forcedManual =
        Collections.unmodifiableMap(new LinkedHashMap<>(forcedManual == null ? Map.of() : forcedManual));
  }

  public MigrationOptions(String basePackage, boolean keepInterfacePrefix) {
    this(basePackage, keepInterfacePrefix, Map.of(), Map.of());
  }

  public static MigrationOptions defaults() {
    return new MigrationOptions(null, false);
  }

  public MigrationOptions withBasePackage(String basePackage) {
    return new MigrationOptions(basePackage, keepInterfacePrefix, bodyOverrides, forcedManual);
  }

  public MigrationOptions withKeepInterfacePrefix(boolean keep) {
    return new MigrationOptions(basePackage, keep, bodyOverrides, forcedManual);
  }

  public MigrationOptions withOverride(String methodKey, BodyOverride body) {
    Map<String, BodyOverride> next = new LinkedHashMap<>(bodyOverrides);
    next.put(methodKey, body);
    Map<String, String> manual = new LinkedHashMap<>(forcedManual);
    manual.remove(methodKey);
    return new MigrationOptions(basePackage, keepInterfacePrefix, next, manual);
  }

  public MigrationOptions withoutOverride(String methodKey) {
    Map<String, BodyOverride> next = new LinkedHashMap<>(bodyOverrides);
    next.remove(methodKey);
    return new MigrationOptions(basePackage, keepInterfacePrefix, next, forcedManual);
  }

  public MigrationOptions withForcedManual(String methodKey, String reason) {
    Map<String, BodyOverride> overrides = new LinkedHashMap<>(bodyOverrides);
    overrides.remove(methodKey);
    Map<String, String> manual = new LinkedHashMap<>(forcedManual);
    manual.put(methodKey, reason);
    return new MigrationOptions(basePackage, keepInterfacePrefix, overrides, manual);
  }
}
