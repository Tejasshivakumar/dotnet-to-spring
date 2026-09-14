package com.portway.core.parse;

import com.portway.core.parse.grammar.CSharpParser;
import java.util.List;

/**
 * The outcome of parsing one .cs file: the parse tree, plus anything the parser objected to.
 *
 * @param tree the compilation unit, present even when there are errors since ANTLR recovers
 */
public record ParseResult(String path, CSharpParser.Compilation_unitContext tree,
    List<SyntaxError> errors) {

  public ParseResult {
    errors = List.copyOf(errors == null ? List.of() : errors);
  }

  public boolean ok() {
    return errors.isEmpty();
  }
}
