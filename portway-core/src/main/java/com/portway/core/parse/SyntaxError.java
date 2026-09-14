package com.portway.core.parse;

/** One error reported by the lexer or parser, anchored in the C# file it came from. */
public record SyntaxError(String path, int line, int column, String message) {

  @Override
  public String toString() {
    return "%s:%d:%d %s".formatted(path, line, column, message);
  }
}
