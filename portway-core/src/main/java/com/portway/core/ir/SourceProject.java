package com.portway.core.ir;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The whole parsed input: every .cs file, the .csproj package list, and appsettings.json.
 *
 * <p>This is the boundary between parsing and everything downstream. Generation reads only from
 * here, never from C# text.
 */
public record SourceProject(
    String name,
    String targetFramework,
    List<PackageRef> packages,
    List<SourceFile> files,
    Map<String, Object> appSettings) {

  public SourceProject {
    packages = List.copyOf(packages == null ? List.of() : packages);
    files = List.copyOf(files == null ? List.of() : files);
    appSettings = Map.copyOf(appSettings == null ? Map.of() : appSettings);
  }

  /** Every type in every file, flattened. */
  public Stream<TypeDecl> allTypes() {
    return files.stream().flatMap(f -> f.types().stream());
  }

  /** Every type the classifier assigned the given role. */
  public Stream<TypeDecl> typesWithRole(ClassRole role) {
    return allTypes().filter(t -> t.role() == role);
  }
}
