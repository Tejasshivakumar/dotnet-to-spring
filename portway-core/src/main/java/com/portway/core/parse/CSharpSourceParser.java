package com.portway.core.parse;

import com.portway.core.parse.grammar.CSharpLexer;
import com.portway.core.parse.grammar.CSharpParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

/**
 * Turns C# text into an ANTLR parse tree.
 *
 * <p>This is the only place in the codebase that knows ANTLR exists beyond the visitor. Everything
 * downstream works from the IR.
 */
public class CSharpSourceParser {

  /** Parses a file from disk. */
  public ParseResult parseFile(Path file) {
    try {
      return parse(Files.readString(file), file.toString());
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
  }

  /** Parses source text. {@code path} is used only to label errors. */
  public ParseResult parse(String source, String path) {
    CharStream chars = CharStreams.fromString(source, path);

    CollectingErrorListener listener = new CollectingErrorListener(path);

    CSharpLexer lexer = new CSharpLexer(chars);
    lexer.removeErrorListeners();
    lexer.addErrorListener(listener);

    CommonTokenStream tokens = new CommonTokenStream(lexer);
    CSharpParser parser = new CSharpParser(tokens);
    parser.removeErrorListeners();
    parser.addErrorListener(listener);

    CSharpParser.Compilation_unitContext tree = parser.compilation_unit();
    return new ParseResult(path, tree, tokens, listener.errors());
  }
}
