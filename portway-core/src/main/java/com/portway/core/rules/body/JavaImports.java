package com.portway.core.rules.body;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Type references inside rewritten body text.
 *
 * <p>A rewritten body is text, but it uses types that need importing: {@code Comparator}, {@code
 * ResponseEntity}. Rather than guess imports afterwards, rules write each such reference as a
 * marker carrying the qualified name, and the emitter turns markers into JavaPoet {@code $T}
 * arguments so imports are exact and collision-safe.
 */
public final class JavaImports {

  private JavaImports() {}

  static final char OPEN = '⟪';
  static final char CLOSE = '⟫';

  private static final Pattern MARKER = Pattern.compile(OPEN + "([\\w.]+)" + CLOSE);

  /** The marker for a qualified type name. */
  public static String ref(String qualifiedName) {
    return OPEN + qualifiedName + CLOSE;
  }

  public static Pattern markerPattern() {
    return MARKER;
  }

  /** Every qualified name referenced by a marker. */
  public static Set<String> referenced(String text) {
    Set<String> names = new LinkedHashSet<>();
    Matcher m = MARKER.matcher(text);
    while (m.find()) {
      names.add(m.group(1));
    }
    return names;
  }

  /** The text with each marker replaced by its simple name, as a Java reader would see it. */
  public static String toSimpleNames(String text) {
    Matcher m = MARKER.matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String qualified = m.group(1);
      m.appendReplacement(
          out, Matcher.quoteReplacement(qualified.substring(qualified.lastIndexOf('.') + 1)));
    }
    m.appendTail(out);
    return out.toString();
  }

  /**
   * Marks every whole-word occurrence of each import's simple name in Java source, outside string
   * literals. Used for LLM output, which arrives as plain text with a separate list of imports.
   */
  public static String markSimpleNames(String javaText, Iterable<String> qualifiedNames) {
    CSharpText.Masked masked = CSharpText.mask(javaText);
    String text = masked.text();
    for (String qualified : qualifiedNames) {
      String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
      text =
          text.replaceAll(
              "(?<![\\w." + OPEN + "])" + Pattern.quote(simple) + "(?![\\w" + CLOSE + "])",
              Matcher.quoteReplacement(ref(qualified)));
    }
    return masked.restore(text);
  }
}
