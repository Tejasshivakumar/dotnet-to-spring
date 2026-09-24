package com.portway.core.generate;

import com.portway.core.ir.PackageRef;
import com.portway.core.ir.SourceProject;
import com.portway.core.report.Finding;
import com.portway.core.report.FindingCode;
import com.portway.core.rules.DependencyMapper;
import com.portway.core.rules.DependencyMapper.MavenDependency;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Writes the generated project's pom.xml from the .csproj package list plus what the generated code
 * itself needs.
 *
 * <p>The Spring Boot parent manages versions, so most dependencies carry none. The Boot version
 * matches the one Portway itself is built and verified against, so a project that passed
 * verification here resolves the same APIs when built for real.
 */
public class PomGenerator {

  /** Kept in step with the parent POM's spring-boot.version. */
  public static final String SPRING_BOOT_VERSION = "3.5.3";

  public static final String SPRINGDOC_VERSION = "2.8.6";

  private final DependencyMapper mapper;

  public PomGenerator(DependencyMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * What the generated code needs regardless of the .csproj.
   *
   * @param jpa entities or repositories were generated
   * @param validation Bean Validation annotations appear in generated code
   * @param security Spring Security annotations appear in generated code
   */
  public record Needs(boolean jpa, boolean validation, boolean security) {}

  public GeneratedFile generate(SourceProject project, String basePackage, Needs needs) {
    List<Finding> findings = new ArrayList<>();
    Map<String, MavenDependency> dependencies = new LinkedHashMap<>();

    // An ASP.NET Core project is a web project whatever its packages say: the
    // framework reference comes from the SDK, not a PackageReference.
    add(dependencies, MavenDependency.parse("org.springframework.boot:spring-boot-starter-web"));
    if (needs.jpa()) {
      add(dependencies, MavenDependency.parse("org.springframework.boot:spring-boot-starter-data-jpa"));
    }
    if (needs.validation()) {
      add(dependencies, MavenDependency.parse("org.springframework.boot:spring-boot-starter-validation"));
    }
    if (needs.security()) {
      add(dependencies, MavenDependency.parse("org.springframework.boot:spring-boot-starter-security"));
    }

    for (PackageRef packageRef : project.packages()) {
      Optional<DependencyMapper.Mapping> mapping = mapper.map(packageRef);
      if (mapping.isEmpty()) {
        findings.add(
            Finding.at(
                FindingCode.UNKNOWN_PACKAGE,
                "NuGet package " + packageRef.id() + " " + nullToEmpty(packageRef.version())
                    + " has no known Maven equivalent. Nothing was added; find a replacement.",
                csprojPath(project),
                0));
        continue;
      }
      mapping.get().dependencies().forEach(d -> add(dependencies, d));
      if (mapping.get().finding() != null) {
        findings.add(
            Finding.at(
                mapping.get().finding(),
                packageRef.id() + ": " + mapping.get().note(),
                csprojPath(project),
                0));
      }
    }
    add(dependencies, MavenDependency.parse("org.springframework.boot:spring-boot-starter-test:test"));

    return new GeneratedFile("pom.xml", pom(project, basePackage, dependencies.values()), null, findings);
  }

  private static void add(Map<String, MavenDependency> deps, MavenDependency dependency) {
    deps.putIfAbsent(dependency.key(), dependency);
  }

  private static String pom(SourceProject project, String basePackage, Iterable<MavenDependency> deps) {
    String artifactId = artifactId(project.name());
    StringBuilder xml = new StringBuilder();
    xml.append(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
          <modelVersion>4.0.0</modelVersion>

          <parent>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-parent</artifactId>
            <version>%s</version>
            <relativePath/>
          </parent>

          <groupId>%s</groupId>
          <artifactId>%s</artifactId>
          <version>0.0.1-SNAPSHOT</version>
          <name>%s</name>
          <description>Migrated from %s%s by Portway</description>

          <properties>
            <java.version>21</java.version>
            <springdoc.version>%s</springdoc.version>
          </properties>

          <dependencies>
        """
            .formatted(
                SPRING_BOOT_VERSION,
                basePackage,
                artifactId,
                xmlEscape(project.name()),
                xmlEscape(project.name()),
                project.targetFramework() == null ? "" : " (" + project.targetFramework() + ")",
                SPRINGDOC_VERSION));
    for (MavenDependency d : deps) {
      xml.append("    <dependency>\n")
          .append("      <groupId>").append(d.groupId()).append("</groupId>\n")
          .append("      <artifactId>").append(d.artifactId()).append("</artifactId>\n");
      if (d.version() != null) {
        xml.append("      <version>").append(d.version()).append("</version>\n");
      }
      if (d.scope() != null) {
        xml.append("      <scope>").append(d.scope()).append("</scope>\n");
      }
      xml.append("    </dependency>\n");
    }
    xml.append(
        """
          </dependencies>

          <build>
            <plugins>
              <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
              </plugin>
            </plugins>
          </build>
        </project>
        """);
    return xml.toString();
  }

  /** {@code BookstoreApi} becomes {@code bookstore-api}. */
  static String artifactId(String projectName) {
    String kebab =
        projectName
            .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
            .replaceAll("([A-Z])([A-Z][a-z])", "$1-$2")
            .replaceAll("[^A-Za-z0-9]+", "-")
            .toLowerCase(Locale.ROOT);
    return kebab.replaceAll("^-+|-+$", "").isEmpty() ? "migrated" : kebab.replaceAll("^-+|-+$", "");
  }

  private static String csprojPath(SourceProject project) {
    return project.name() + ".csproj";
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private static String xmlEscape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
