package com.portway.core.generate;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.portway.core.rules.JavaType;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates the initializers C# properties carry, such as {@code = string.Empty} or {@code = new
 * List<Book>()}.
 *
 * <p>These matter more than they look. A C# model relies on them for non-null defaults, and a Java
 * field without one starts as null: a {@code @NotNull String title} that is null by default, or an
 * association collection that throws on the first {@code add}. Only initializers with an exact
 * translation are carried over; anything else returns null and the caller reports it.
 */
public final class Initializers {

  private Initializers() {}

  private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([lLfFdD])?");
  private static final Pattern DECIMAL = Pattern.compile("(-?\\d+(?:\\.\\d+)?)[mM]?");
  private static final Pattern NEW_COLLECTION =
      Pattern.compile("new\\s*(\\w+\\s*<[^>]*>)?\\s*\\(\\s*\\)|new\\s+\\w+\\s*<[^>]*>\\s*\\{\\s*}|\\[\\s*]");
  private static final Pattern MEMBER = Pattern.compile("([A-Z]\\w*)\\.([A-Z]\\w*)");

  /**
   * @param csharp the initializer source, e.g. {@code string.Empty}
   * @param type the field's resolved Java type
   * @param context for placing enum types
   * @return the Java initializer, or null when there is none or it has no exact translation
   */
  public static CodeBlock translate(String csharp, JavaType type, GenerationContext context) {
    if (csharp == null || csharp.isBlank()) {
      return null;
    }
    String init = csharp.strip();
    if (init.equals("null") || init.equals("default") || init.equals("null!")) {
      return null;
    }
    String name = type.qualifiedName();

    if (init.equals("string.Empty") || init.equals("String.Empty")) {
      return CodeBlock.of("$S", "");
    }
    if (init.startsWith("\"") && init.endsWith("\"") && !init.contains("{")) {
      return CodeBlock.of("$L", init);
    }
    if (name.equals("java.math.BigDecimal")) {
      Matcher d = DECIMAL.matcher(init);
      if (d.matches()) {
        return d.group(1).matches("-?0+(\\.0+)?")
            ? CodeBlock.of("$T.ZERO", ClassName.get("java.math", "BigDecimal"))
            : CodeBlock.of("new $T($S)", ClassName.get("java.math", "BigDecimal"), d.group(1));
      }
      return null;
    }
    if (init.equals("true") || init.equals("false")) {
      return CodeBlock.of("$L", init);
    }
    if (NUMBER.matcher(init).matches()) {
      boolean isLong = name.equals("long") || name.equals("java.lang.Long");
      boolean isDouble = name.equals("double") || name.equals("java.lang.Double");
      boolean isFloat = name.equals("float") || name.equals("java.lang.Float");
      String digits = init.replaceAll("[lLfFdD]$", "");
      return CodeBlock.of(
          "$L", digits + (isLong ? "L" : isFloat ? "f" : isDouble && !digits.contains(".") ? ".0" : ""));
    }
    if (NEW_COLLECTION.matcher(init).matches()) {
      return switch (name) {
        case "java.util.List", "java.util.Collection" ->
            CodeBlock.of("new $T<>()", ClassName.get("java.util", "ArrayList"));
        case "java.util.Set" -> CodeBlock.of("new $T<>()", ClassName.get("java.util", "HashSet"));
        case "java.util.Map" -> CodeBlock.of("new $T<>()", ClassName.get("java.util", "HashMap"));
        default -> null;
      };
    }
    if (init.equals("DateTime.UtcNow")) {
      return CodeBlock.of(
          "$T.now($T.UTC)",
          ClassName.get("java.time", "LocalDateTime"),
          ClassName.get("java.time", "ZoneOffset"));
    }
    if (init.equals("DateTime.Now")) {
      return CodeBlock.of("$T.now()", ClassName.get("java.time", "LocalDateTime"));
    }
    if (init.equals("Guid.NewGuid()")) {
      return CodeBlock.of("$T.randomUUID()", ClassName.get("java.util", "UUID"));
    }
    Matcher member = MEMBER.matcher(init);
    if (member.matches() && context != null && context.isEnum(member.group(1))) {
      return CodeBlock.of(
          "$T.$L",
          ClassName.get(context.packageOf(member.group(1)), member.group(1)),
          member.group(2));
    }
    return null;
  }
}
