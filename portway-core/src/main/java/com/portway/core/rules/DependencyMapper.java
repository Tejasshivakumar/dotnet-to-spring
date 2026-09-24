package com.portway.core.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.portway.core.ir.PackageRef;
import com.portway.core.report.FindingCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps NuGet packages from the .csproj onto Maven dependencies, using {@code
 * dependency-mappings.yaml}.
 *
 * <p>An unknown package adds nothing and is reported. Guessing at a Maven artifact for a library we
 * know nothing about would produce a pom that resolves and a project that silently lacks whatever
 * the library did.
 */
public class DependencyMapper {

  /** One Maven dependency. {@code version} and {@code scope} may be null. */
  public record MavenDependency(String groupId, String artifactId, String version, String scope) {

    public static MavenDependency parse(String coordinates) {
      String[] parts = coordinates.split(":");
      if (parts.length < 2) {
        throw new IllegalArgumentException("Bad Maven coordinates: " + coordinates);
      }
      String version = null;
      String scope = null;
      if (parts.length == 3) {
        if (Character.isDigit(parts[2].charAt(0)) || parts[2].startsWith("$")) {
          version = parts[2];
        } else {
          scope = parts[2];
        }
      } else if (parts.length >= 4) {
        version = parts[2];
        scope = parts[3];
      }
      return new MavenDependency(parts[0], parts[1], version, scope);
    }

    public String key() {
      return groupId + ":" + artifactId;
    }
  }

  /** What one package mapped to. */
  public record Mapping(
      PackageRef source, List<MavenDependency> dependencies, FindingCode finding, String note) {}

  private record Rule(String match, List<MavenDependency> maven, FindingCode finding, String note) {
    boolean matches(String packageId) {
      if (match.endsWith("*")) {
        return packageId.startsWith(match.substring(0, match.length() - 1));
      }
      return packageId.equalsIgnoreCase(match);
    }
  }

  private final List<Rule> rules;

  private DependencyMapper(List<Rule> rules) {
    this.rules = List.copyOf(rules);
  }

  public static DependencyMapper fromDefaults() {
    try (InputStream in = DependencyMapper.class.getResourceAsStream("/dependency-mappings.yaml")) {
      if (in == null) {
        throw new IllegalStateException("Missing resource /dependency-mappings.yaml");
      }
      return fromYaml(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read dependency mappings", e);
    }
  }

  @SuppressWarnings("unchecked")
  public static DependencyMapper fromYaml(InputStream yaml) {
    try {
      Map<String, Object> root = new ObjectMapper(new YAMLFactory()).readValue(yaml, Map.class);
      List<Rule> rules = new ArrayList<>();
      for (Map<String, Object> raw : (List<Map<String, Object>>) root.getOrDefault("packages", List.of())) {
        List<MavenDependency> maven =
            ((List<String>) raw.getOrDefault("maven", List.of())).stream().map(MavenDependency::parse).toList();
        Object finding = raw.get("finding");
        rules.add(
            new Rule(
                (String) raw.get("match"),
                maven,
                finding == null ? null : FindingCode.valueOf(finding.toString()),
                (String) raw.get("note")));
      }
      return new DependencyMapper(rules);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot parse dependency mappings", e);
    }
  }

  /** The mapping for a package, or empty when no rule knows it. */
  public Optional<Mapping> map(PackageRef packageRef) {
    return rules.stream()
        .filter(r -> r.matches(packageRef.id()))
        .findFirst()
        .map(r -> new Mapping(packageRef, r.maven(), r.finding(), r.note()));
  }
}
