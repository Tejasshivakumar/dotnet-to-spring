package com.portway.core.rules.body;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, careful text operations over C# and Java source fragments.
 *
 * <p>Body rewriting is token-level, not a parser, so everything here is about not being fooled by
 * the two things that break naive regexes: string literals and nested brackets. Strings and comments
 * are masked out before any rule runs, and every "find the argument" operation balances brackets.
 */
public final class CSharpText {

  private CSharpText() {}

  private static final char MASK_OPEN = '\u0002';
  private static final char MASK_CLOSE = '\u0003';

  /**
   * Source with every string literal, char literal and comment replaced by an opaque placeholder.
   *
   * <p>Placeholders contain no word characters, quotes or brackets, so identifier and bracket rules
   * run over the masked text without ever touching the inside of a literal.
   */
  public record Masked(String text, List<String> literals) {

    /** Puts the original literals back. */
    public String restore() {
      return restore(text);
    }

    /** Puts the original literals back into a text derived from {@link #text()}. */
    public String restore(String derived) {
      StringBuilder out = new StringBuilder();
      int i = 0;
      while (i < derived.length()) {
        char c = derived.charAt(i);
        if (c == MASK_OPEN) {
          int end = derived.indexOf(MASK_CLOSE, i);
          int index = Integer.parseInt(derived.substring(i + 1, end));
          out.append(literals.get(index));
          i = end + 1;
        } else {
          out.append(c);
          i++;
        }
      }
      return out.toString();
    }
  }

  /** Masks Java-style string literals, char literals and comments. */
  public static Masked mask(String source) {
    List<String> literals = new ArrayList<>();
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < source.length()) {
      char c = source.charAt(i);
      int end = -1;
      if (c == '"') {
        end = endOfQuoted(source, i, '"');
      } else if (c == '\'') {
        end = endOfQuoted(source, i, '\'');
      } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
        int newline = source.indexOf('\n', i);
        end = newline < 0 ? source.length() : newline;
      } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
        int close = source.indexOf("*/", i + 2);
        end = close < 0 ? source.length() : close + 2;
      }
      if (end > i) {
        out.append(MASK_OPEN).append(literals.size()).append(MASK_CLOSE);
        literals.add(source.substring(i, end));
        i = end;
      } else {
        out.append(c);
        i++;
      }
    }
    return new Masked(out.toString(), literals);
  }

  /**
   * The source with every literal and comment blanked to spaces, newlines kept. Offsets and line
   * numbers are unchanged, which is what mapping a compiler error back to a method needs.
   */
  public static String blank(String source) {
    StringBuilder out = new StringBuilder(source);
    int i = 0;
    while (i < source.length()) {
      char c = source.charAt(i);
      int end = -1;
      if (c == '"' && source.startsWith("\"\"\"", i)) {
        int close = source.indexOf("\"\"\"", i + 3);
        end = close < 0 ? source.length() : close + 3;
      } else if (c == '"') {
        end = endOfQuoted(source, i, '"');
      } else if (c == '\'') {
        end = endOfQuoted(source, i, '\'');
      } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
        int newline = source.indexOf('\n', i);
        end = newline < 0 ? source.length() : newline;
      } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
        int close = source.indexOf("*/", i + 2);
        end = close < 0 ? source.length() : close + 2;
      }
      if (end > i) {
        for (int j = i; j < end; j++) {
          if (out.charAt(j) != '\n') {
            out.setCharAt(j, ' ');
          }
        }
        i = end;
      } else {
        i++;
      }
    }
    return out.toString();
  }

  /** Index just past the closing quote of a literal starting at {@code start}. */
  private static int endOfQuoted(String s, int start, char quote) {
    int i = start + 1;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == quote) {
        return i + 1;
      }
      if (c == '\n') {
        // Unterminated: treat the rest of the line as the literal rather than
        // swallowing the file.
        return i;
      }
      i++;
    }
    return s.length();
  }

  /**
   * Index of the bracket closing the one at {@code open}, or -1. Works on masked text, where no
   * bracket can hide inside a literal.
   */
  public static int matching(String text, int open) {
    char o = text.charAt(open);
    char c =
        switch (o) {
          case '(' -> ')';
          case '[' -> ']';
          case '{' -> '}';
          case '<' -> '>';
          default -> throw new IllegalArgumentException("Not an opening bracket: " + o);
        };
    int depth = 0;
    for (int i = open; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == o) {
        depth++;
      } else if (ch == c) {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  /** Splits on {@code separator} where it is not nested inside any bracket. */
  public static List<String> splitTopLevel(String text, char separator) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '(' || ch == '[' || ch == '{') {
        depth++;
      } else if (ch == ')' || ch == ']' || ch == '}') {
        depth--;
      } else if (ch == separator && depth == 0) {
        parts.add(text.substring(start, i));
        start = i + 1;
      }
    }
    String last = text.substring(start);
    if (!last.isBlank() || !parts.isEmpty()) {
      parts.add(last);
    }
    return parts;
  }

  /**
   * Replaces every call whose opening text matches {@code head} (a regex ending just before the
   * opening parenthesis) with whatever {@code replacement} returns for its argument list. The
   * replacement receives the arguments already split at top-level commas and trimmed; returning
   * null leaves that call untouched.
   */
  public static String replaceCalls(
      String text, Pattern head, Function<CallSite, String> replacement) {
    StringBuilder out = new StringBuilder();
    int from = 0;
    Matcher m = head.matcher(text);
    while (m.find(from)) {
      int open = m.end();
      if (open >= text.length() || text.charAt(open) != '(') {
        out.append(text, from, m.end());
        from = m.end();
        continue;
      }
      int close = matching(text, open);
      if (close < 0) {
        break;
      }
      List<String> args =
          splitTopLevel(text.substring(open + 1, close), ',').stream().map(String::strip).toList();
      String replaced = replacement.apply(new CallSite(m, args));
      out.append(text, from, m.start());
      if (replaced == null) {
        out.append(text, m.start(), close + 1);
      } else {
        out.append(replaced);
      }
      from = close + 1;
    }
    out.append(text.substring(Math.min(from, text.length())));
    return out.toString();
  }

  /** One matched call: the regex match of its head, and its arguments. */
  public record CallSite(Matcher head, List<String> args) {
    public String group(int group) {
      return head.group(group);
    }
  }

  /**
   * Start index of the receiver expression that ends just before {@code end}: walks back over
   * identifiers, member access, calls and indexers, so for {@code _db.Books.Where(x)} ending before
   * {@code .Where} it returns the index of {@code _db}.
   */
  public static int receiverStart(String text, int end) {
    int i = end - 1;
    while (i >= 0) {
      char c = text.charAt(i);
      if (c == ')' || c == ']') {
        int open = matchingBackwards(text, i);
        if (open < 0) {
          return i + 1;
        }
        i = open - 1;
      } else if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == MASK_CLOSE) {
        if (c == MASK_CLOSE) {
          i = text.lastIndexOf(MASK_OPEN, i) - 1;
        } else {
          i--;
        }
      } else {
        break;
      }
    }
    return i + 1;
  }

  private static int matchingBackwards(String text, int close) {
    char c = text.charAt(close);
    char o = c == ')' ? '(' : '[';
    int depth = 0;
    for (int i = close; i >= 0; i--) {
      char ch = text.charAt(i);
      if (ch == c) {
        depth++;
      } else if (ch == o) {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  /** Index of the next {@code ;} at bracket depth zero from {@code from}, or -1. */
  public static int endOfStatement(String text, int from) {
    int depth = 0;
    for (int i = from; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '(' || ch == '[' || ch == '{') {
        depth++;
      } else if (ch == ')' || ch == ']' || ch == '}') {
        if (depth == 0) {
          return -1;
        }
        depth--;
      } else if (ch == ';' && depth == 0) {
        return i;
      }
    }
    return -1;
  }

  /** Removes the outer braces of a block and the common leading indentation of its lines. */
  public static String unwrapBlock(String block) {
    String body = block.strip();
    if (body.startsWith("{") && body.endsWith("}")) {
      body = body.substring(1, body.length() - 1);
    }
    List<String> lines = new ArrayList<>(List.of(body.split("\n", -1)));
    while (!lines.isEmpty() && lines.get(0).isBlank()) {
      lines.remove(0);
    }
    while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
      lines.remove(lines.size() - 1);
    }
    int indent = Integer.MAX_VALUE;
    for (String line : lines) {
      if (!line.isBlank()) {
        indent = Math.min(indent, line.length() - line.stripLeading().length());
      }
    }
    StringBuilder out = new StringBuilder();
    for (String line : lines) {
      out.append(line.isBlank() ? "" : line.substring(Math.min(indent, line.length())))
          .append('\n');
    }
    return out.toString();
  }
}
