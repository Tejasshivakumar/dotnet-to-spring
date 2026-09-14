package com.portway.core.generate;

import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;

/**
 * Runs generated source through google-java-format.
 *
 * <p>Worth a dedicated pass because the generator interpolates raw rewritten C# bodies into
 * otherwise tidy JavaPoet output, and the result is inconsistent before formatting. The diff view
 * is the first thing anyone sees in the demo, so it should not look like machine output.
 *
 * <p>Formatting failures are not fatal: unformatted valid Java is better than no file, and the
 * compile verifier is what actually decides whether the output is good.
 */
public class JavaFormatter {

  private final Formatter formatter = new Formatter();

  /** @return formatted source, or the input unchanged if it could not be parsed */
  public String format(String javaSource) {
    try {
      return formatter.formatSource(javaSource);
    } catch (FormatterException e) {
      return javaSource;
    }
  }

  /** @return true when the source parsed cleanly enough to format */
  public boolean canFormat(String javaSource) {
    try {
      formatter.formatSource(javaSource);
      return true;
    } catch (FormatterException e) {
      return false;
    }
  }
}
