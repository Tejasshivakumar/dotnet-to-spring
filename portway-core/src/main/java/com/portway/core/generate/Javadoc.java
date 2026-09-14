package com.portway.core.generate;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts C# XML doc comments into Javadoc.
 *
 * <p>Left as raw text the {@code <summary>} tags survive into the generated Javadoc, where they
 * render as stray markup. The two conventions carry the same information under different tags, so
 * the mapping is mostly a rename.
 */
public final class Javadoc {

  private Javadoc() {}

  public static String fromXmlDoc(String xmlDoc) {
    if (xmlDoc == null || xmlDoc.isBlank()) {
      return null;
    }
    List<String> summary = new ArrayList<>();
    List<String> tail = new ArrayList<>();

    for (String line : xmlDoc.split("\\R")) {
      String text = line.strip();
      if (text.startsWith("<summary>") || text.equals("</summary>") || text.isEmpty()) {
        text = text.replace("<summary>", "").replace("</summary>", "").strip();
        if (text.isEmpty()) {
          continue;
        }
      }
      // <param name="x">desc</param> becomes @param x desc
      String param = rewriteTag(text, "param", "@param ");
      if (param != null) {
        tail.add(param);
        continue;
      }
      String returns = rewriteTag(text, "returns", "@return ");
      if (returns != null) {
        tail.add(returns);
        continue;
      }
      if (text.startsWith("<") && text.endsWith(">")) {
        continue;
      }
      summary.add(text);
    }

    List<String> all = new ArrayList<>(summary);
    all.addAll(tail);
    return all.isEmpty() ? null : String.join("\n", all);
  }

  private static String rewriteTag(String line, String tag, String javadocTag) {
    String open = "<" + tag;
    String close = "</" + tag + ">";
    if (!line.startsWith(open) || !line.endsWith(close)) {
      return null;
    }
    int contentStart = line.indexOf('>') + 1;
    String content = line.substring(contentStart, line.length() - close.length()).strip();

    // <param name="id">the id</param>
    int nameStart = line.indexOf("name=\"");
    if (nameStart >= 0 && nameStart < contentStart) {
      int nameEnd = line.indexOf('"', nameStart + 6);
      String name = line.substring(nameStart + 6, nameEnd);
      return javadocTag + name + " " + content;
    }
    return javadocTag + content;
  }
}
