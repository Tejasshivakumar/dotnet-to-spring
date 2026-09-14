package com.portway.core.parse;

import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

/**
 * Gathers syntax errors instead of printing them to stderr.
 *
 * <p>ANTLR's default listener writes to the console and carries on, which would let a file that
 * failed to parse quietly produce a half-empty IR and, from there, plausible-looking but wrong
 * Java. Everything here is surfaced to the caller.
 */
public class CollectingErrorListener extends BaseErrorListener {

  private final String path;
  private final List<SyntaxError> errors = new ArrayList<>();

  public CollectingErrorListener(String path) {
    this.path = path;
  }

  @Override
  public void syntaxError(
      Recognizer<?, ?> recognizer,
      Object offendingSymbol,
      int line,
      int charPositionInLine,
      String msg,
      RecognitionException e) {
    errors.add(new SyntaxError(path, line, charPositionInLine, msg));
  }

  public List<SyntaxError> errors() {
    return List.copyOf(errors);
  }

  public boolean hasErrors() {
    return !errors.isEmpty();
  }
}
