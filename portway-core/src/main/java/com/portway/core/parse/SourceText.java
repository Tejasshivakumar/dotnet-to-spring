package com.portway.core.parse;

import com.portway.core.parse.grammar.CSharpLexer;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;

/** Recovers original source text, whitespace intact, from parse tree contexts. */
public final class SourceText {

  private SourceText() {}

  /**
   * The exact source text a context covers.
   *
   * <p>{@code ctx.getText()} concatenates token text and so strips every space and newline, which
   * makes it useless for method bodies. Reading the character interval off the input stream keeps
   * the source as the developer wrote it.
   */
  public static String of(ParserRuleContext ctx) {
    if (ctx == null || ctx.getStart() == null || ctx.getStop() == null) {
      return null;
    }
    Interval span = Interval.of(ctx.getStart().getStartIndex(), ctx.getStop().getStopIndex());
    return ctx.getStart().getInputStream().getText(span);
  }

  /**
   * The XML doc comment immediately above a declaration, with the {@code ///} markers stripped, or
   * null when there is none.
   *
   * <p>Doc comments sit on a hidden channel, so they are invisible to the tree and have to be found
   * by walking back through the token stream.
   */
  public static String docCommentAbove(CommonTokenStream tokens, ParserRuleContext ctx) {
    if (tokens == null || ctx == null || ctx.getStart() == null) {
      return null;
    }
    List<Token> hidden =
        tokens.getHiddenTokensToLeft(ctx.getStart().getTokenIndex(), CSharpLexer.COMMENTS_CHANNEL);
    if (hidden == null || hidden.isEmpty()) {
      return null;
    }
    List<String> lines = new ArrayList<>();
    for (Token t : hidden) {
      if (t.getType() != CSharpLexer.SINGLE_LINE_DOC_COMMENT
          && t.getType() != CSharpLexer.DELIMITED_DOC_COMMENT) {
        continue;
      }
      for (String line : t.getText().split("\\R")) {
        String cleaned = line.strip();
        if (cleaned.startsWith("///")) {
          cleaned = cleaned.substring(3).strip();
        }
        lines.add(cleaned);
      }
    }
    // Keep only the block that actually sits above this declaration: the doc comment
    // tokens come back oldest-first, and anything before a blank run belongs to a
    // previous member.
    while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
      lines.remove(lines.size() - 1);
    }
    while (!lines.isEmpty() && lines.get(0).isEmpty()) {
      lines.remove(0);
    }
    return lines.isEmpty() ? null : String.join("\n", lines);
  }
}
