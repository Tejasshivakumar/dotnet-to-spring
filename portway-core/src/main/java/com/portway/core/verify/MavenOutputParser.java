package com.portway.core.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pulls compiler errors out of Maven's console output. */
public final class MavenOutputParser {

  private MavenOutputParser() {}

  /** {@code [ERROR] /workspace/src/main/java/com/example/Foo.java:[24,17] cannot find symbol}. */
  private static final Pattern ERROR =
      Pattern.compile("^\\[ERROR\\]\\s+(.+?\\.java):\\[(\\d+),(\\d+)\\]\\s+(.+)$", Pattern.MULTILINE);

  /**
   * @param projectRoot the directory prefix the build saw, such as {@code /workspace}, stripped so
   *     paths match the generated project's own
   */
  public static List<CompileError> parse(String output, String projectRoot) {
    List<CompileError> errors = new ArrayList<>();
    Matcher m = ERROR.matcher(output);
    while (m.find()) {
      String path = m.group(1).replace('\\', '/');
      String root = projectRoot.endsWith("/") ? projectRoot : projectRoot + "/";
      if (path.startsWith(root)) {
        path = path.substring(root.length());
      }
      CompileError error =
          new CompileError(path, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), m.group(4).strip());
      if (!errors.contains(error)) {
        errors.add(error);
      }
    }
    return errors;
  }
}
