package com.portway.core.ir;

import java.util.List;

/** One parsed .cs file. */
public record SourceFile(
    String path, String namespaceName, List<String> usings, List<TypeDecl> types) {

  public SourceFile {
    usings = List.copyOf(usings == null ? List.of() : usings);
    types = List.copyOf(types == null ? List.of() : types);
  }
}
