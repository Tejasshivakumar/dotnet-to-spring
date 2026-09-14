package com.portway.core.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portway.core.ir.PackageRef;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.SourceProject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads a directory of C# into a {@link SourceProject}: every .cs file, the .csproj package list,
 * and appsettings.json.
 *
 * <p>Parse failures are collected rather than thrown. A project where one file has an unsupported
 * construct should still migrate the other twenty, with that one file reported.
 */
public class ProjectLoader {

  private final CSharpSourceParser parser = new CSharpSourceParser();

  /** Everything the loader could not parse, so callers can turn it into findings. */
  public record Load(SourceProject project, List<SyntaxError> errors) {
    public Load {
      errors = List.copyOf(errors == null ? List.of() : errors);
    }
  }

  public Load load(Path sourceDir) {
    List<SourceFile> files = new ArrayList<>();
    List<SyntaxError> errors = new ArrayList<>();
    String entryPointSource = null;

    for (Path file : csharpFiles(sourceDir)) {
      String name = file.getFileName().toString();
      // Program.cs and Startup.cs use top-level statements, which the grammar
      // predates. Their text is kept for ProgramScanner instead of being parsed.
      if (name.equals("Program.cs") || name.equals("Startup.cs")) {
        entryPointSource = read(file);
        continue;
      }

      ParseResult parsed = parser.parseFile(file);
      errors.addAll(parsed.errors());
      if (parsed.ok()) {
        SourceFile source = new CSharpToIrVisitor(parsed.tokens()).toSourceFile(parsed);
        files.add(withRelativePath(source, sourceDir, file));
      }
    }

    Path csproj = findFirst(sourceDir, ".csproj");
    List<PackageRef> packages = csproj == null ? List.of() : readPackages(csproj);
    String targetFramework = csproj == null ? null : readTargetFramework(csproj);
    String projectName =
        csproj == null
            ? sourceDir.getFileName().toString()
            : stripExtension(csproj.getFileName().toString());

    Map<String, Object> appSettings = readAppSettings(sourceDir);

    return new Load(
        new SourceProject(
            projectName, targetFramework, packages, files, appSettings, entryPointSource),
        errors);
  }

  /**
   * Paths are stored relative to the project root. Absolute paths would leak the developer's
   * directory layout into the database and into every finding shown in the UI.
   */
  private static SourceFile withRelativePath(SourceFile source, Path root, Path file) {
    String relative = root.relativize(file).toString().replace('\\', '/');
    return new SourceFile(relative, source.namespaceName(), source.usings(), source.types());
  }

  private static List<Path> csharpFiles(Path root) {
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".cs"))
          // Build output: obj/ holds generated AssemblyInfo and GlobalUsings files
          // that are not part of the source project.
          .filter(p -> !containsDirectory(root, p, "obj") && !containsDirectory(root, p, "bin"))
          .sorted(Comparator.comparing(Path::toString))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot walk " + root, e);
    }
  }

  private static boolean containsDirectory(Path root, Path file, String directory) {
    Path relative = root.relativize(file);
    for (Path segment : relative) {
      if (segment.toString().equals(directory)) {
        return true;
      }
    }
    return false;
  }

  private static Path findFirst(Path root, String extension) {
    try (Stream<Path> walk = Files.walk(root, 2)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(extension))
          .sorted(Comparator.comparing(Path::toString))
          .findFirst()
          .orElse(null);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot walk " + root, e);
    }
  }

  private static List<PackageRef> readPackages(Path csproj) {
    List<PackageRef> packages = new ArrayList<>();
    NodeList references = csprojElements(csproj, "PackageReference");
    for (int i = 0; i < references.getLength(); i++) {
      Element element = (Element) references.item(i);
      String id = element.getAttribute("Include");
      String version = element.getAttribute("Version");
      if (!id.isEmpty()) {
        packages.add(new PackageRef(id, version.isEmpty() ? null : version));
      }
    }
    return packages;
  }

  private static String readTargetFramework(Path csproj) {
    NodeList frameworks = csprojElements(csproj, "TargetFramework");
    return frameworks.getLength() == 0 ? null : frameworks.item(0).getTextContent().trim();
  }

  private static NodeList csprojElements(Path csproj, String tag) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      // The .csproj is untrusted input: disable external entity resolution.
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      return factory
          .newDocumentBuilder()
          .parse(csproj.toFile())
          .getElementsByTagName(tag);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot parse " + csproj, e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readAppSettings(Path root) {
    Path settings = root.resolve("appsettings.json");
    if (!Files.isRegularFile(settings)) {
      return Map.of();
    }
    try {
      return new LinkedHashMap<>(
          new ObjectMapper().readValue(settings.toFile(), Map.class));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot parse " + settings, e);
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
  }

  private static String stripExtension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot < 0 ? fileName : fileName.substring(0, dot);
  }
}
