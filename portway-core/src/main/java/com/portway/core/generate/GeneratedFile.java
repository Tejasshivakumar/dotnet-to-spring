package com.portway.core.generate;

import com.portway.core.report.Finding;
import java.util.List;

/**
 * One file the generator produced.
 *
 * @param path path within the generated project, forward slashes
 * @param sourcePath the C# file it came from, or null for files with no single origin such as
 *     pom.xml
 */
public record GeneratedFile(
    String path, String content, String sourcePath, List<Finding> findings) {

  public GeneratedFile {
    findings = List.copyOf(findings == null ? List.of() : findings);
  }
}
