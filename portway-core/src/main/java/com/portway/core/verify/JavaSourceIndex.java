package com.portway.core.verify;

import com.portway.core.report.MethodReport;
import com.portway.core.rules.body.CSharpText;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds which generated method a line of a generated file belongs to.
 *
 * <p>This is what turns "line 57 of BookServiceImpl.java does not compile" into "BookService.
 * CreateAsync needs demoting", and so what lets one bad method be stubbed without throwing away the
 * rest of the file. Line numbers are read from the final, formatted file, because that is what the
 * compiler saw.
 */
public final class JavaSourceIndex {

  /** A method's extent in a file, lines inclusive and 1-based. */
  public record Span(String name, int paramCount, int startLine, int endLine) {
    boolean contains(int line) {
      return line >= startLine && line <= endLine;
    }
  }

  private final List<Span> spans;

  private JavaSourceIndex(List<Span> spans) {
    this.spans = List.copyOf(spans);
  }

  public List<Span> spans() {
    return spans;
  }

  private static final Pattern NAME = Pattern.compile("\\b([a-z_$][\\w$]*)\\s*\\(");
  private static final Pattern DECLARATION_PREFIX =
      Pattern.compile("[\\w>\\]?]\\s+$");
  private static final Pattern NOT_A_DECLARATION =
      Pattern.compile("\\b(return|new|throw|else|case)\\s+$");

  /** Indexes every method body in the file. */
  public static JavaSourceIndex of(String javaSource) {
    String text = CSharpText.blank(javaSource);
    List<Span> spans = new ArrayList<>();
    Matcher m = NAME.matcher(text);
    int from = 0;
    while (m.find(from)) {
      from = m.end();
      String before = text.substring(lineStart(text, m.start()), m.start());
      // A declaration has a return type right before the name; a call does not.
      if (!DECLARATION_PREFIX.matcher(before).find()
          || NOT_A_DECLARATION.matcher(before).find()
          || before.contains("=")) {
        continue;
      }
      int open = m.end() - 1;
      int close = CSharpText.matching(text, open);
      if (close < 0) {
        continue;
      }
      int brace = close + 1;
      while (brace < text.length() && text.charAt(brace) != '{' && text.charAt(brace) != ';') {
        brace++;
      }
      if (brace >= text.length() || text.charAt(brace) != '{') {
        continue;
      }
      int end = CSharpText.matching(text, brace);
      if (end < 0) {
        continue;
      }
      String params = text.substring(open + 1, close).strip();
      int count = params.isEmpty() ? 0 : CSharpText.splitTopLevel(params, ',').size();
      // Annotations sit above the name and belong to the method too.
      int startLine = lineOf(text, annotationStart(text, lineStart(text, m.start())));
      spans.add(new Span(m.group(1), count, startLine, lineOf(text, end)));
      from = end + 1;
    }
    return new JavaSourceIndex(spans);
  }

  /** The method containing {@code line}, if any. */
  public Optional<Span> methodAt(int line) {
    return spans.stream().filter(s -> s.contains(line)).findFirst();
  }

  /** The report for the method containing {@code line}, matched by name and arity. */
  public Optional<MethodReport> reportAt(int line, List<MethodReport> candidates) {
    return methodAt(line)
        .flatMap(
            span ->
                candidates.stream()
                    .filter(r -> signatureName(r).equals(span.name()) && signatureArity(r) == span.paramCount())
                    .findFirst());
  }

  static String signatureName(MethodReport report) {
    Matcher m = Pattern.compile("(\\w+)\\s*\\(").matcher(report.javaSignature());
    return m.find() ? m.group(1) : "";
  }

  static int signatureArity(MethodReport report) {
    String signature = report.javaSignature();
    int open = signature.indexOf('(');
    int close = signature.lastIndexOf(')');
    String params = open < 0 || close < open ? "" : signature.substring(open + 1, close).strip();
    return params.isEmpty() ? 0 : CSharpText.splitTopLevel(params.replace('<', '(').replace('>', ')'), ',').size();
  }

  private static int lineStart(String text, int index) {
    int nl = text.lastIndexOf('\n', index - 1);
    return nl + 1;
  }

  /** Walks up over annotation-only lines immediately above a declaration. */
  private static int annotationStart(String text, int declarationLineStart) {
    int start = declarationLineStart;
    while (start > 0) {
      int previous = lineStart(text, start - 1);
      String line = text.substring(previous, start).strip();
      if (!line.startsWith("@")) {
        break;
      }
      start = previous;
    }
    return start;
  }

  private static int lineOf(String text, int index) {
    int line = 1;
    for (int i = 0; i < index && i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }
}
