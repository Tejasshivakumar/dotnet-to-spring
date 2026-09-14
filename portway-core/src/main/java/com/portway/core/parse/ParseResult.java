package com.portway.core.parse;

import com.portway.core.parse.grammar.CSharpParser;
import java.util.List;
import org.antlr.v4.runtime.CommonTokenStream;

/**
 * The outcome of parsing one .cs file: the parse tree, the token stream behind it, and anything the
 * parser objected to.
 *
 * <p>The token stream is kept because doc comments live on a hidden channel and are reachable only
 * through it, not through the tree.
 *
 * @param tree the compilation unit, present even when there are errors since ANTLR recovers
 */
public record ParseResult(
    String path,
    CSharpParser.Compilation_unitContext tree,
    CommonTokenStream tokens,
    List<SyntaxError> errors) {

  public ParseResult {
    errors = List.copyOf(errors == null ? List.of() : errors);
  }

  public boolean ok() {
    return errors.isEmpty();
  }
}
