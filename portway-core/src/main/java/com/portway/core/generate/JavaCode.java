package com.portway.core.generate;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;
import com.portway.core.rules.body.JavaImports;
import java.util.regex.Matcher;

/** Turns rewritten body text into JavaPoet code, and signatures back into readable text. */
public final class JavaCode {

  private JavaCode() {}

  /**
   * A body whose type references are import markers becomes a CodeBlock with one {@code $T} per
   * marker, so JavaPoet writes the imports and resolves any simple-name collision itself.
   */
  public static CodeBlock fromMarkedText(String body) {
    CodeBlock.Builder code = CodeBlock.builder();
    Matcher m = JavaImports.markerPattern().matcher(body);
    int from = 0;
    while (m.find()) {
      if (m.start() > from) {
        code.add("$L", body.substring(from, m.start()));
      }
      code.add("$T", ClassName.bestGuess(m.group(1)));
      from = m.end();
    }
    if (from < body.length()) {
      code.add("$L", body.substring(from));
    }
    if (!body.endsWith("\n")) {
      code.add("\n");
    }
    return code.build();
  }

  /** {@code java.util.List<bookstoreapi.dto.BookResponse>} reads as {@code List<BookResponse>}. */
  public static String simpleNames(TypeName type) {
    return simpleNames(type.toString());
  }

  public static String simpleNames(String qualifiedText) {
    return qualifiedText.replaceAll("(?:\\b[a-z_][\\w]*\\.)+(?=[A-Z])", "");
  }

  /**
   * Escapes text for a Javadoc block: HTML-significant characters, a comment terminator, and
   * {@code @} which Javadoc would read as a tag.
   */
  public static String javadocEscape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("@", "&#64;")
        .replace("*/", "*&#47;");
  }
}
