package com.portway.core.generate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.portway.core.ir.SourceProject;
import com.portway.core.report.Finding;
import com.portway.core.report.FindingCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates appsettings.json into application.yml.
 *
 * <p>Known keys move to their Spring homes: the default connection string becomes {@code
 * spring.datasource}, log levels become {@code logging.level}, the Kestrel URL becomes {@code
 * server.port}. Everything else is preserved under an {@code app:} prefix with kebab-case keys, so
 * nothing the application configured silently disappears.
 */
public class ApplicationYmlGenerator {

  private static final String SETTINGS = "appsettings.json";

  private static final Map<String, String> LOG_LEVELS =
      Map.of(
          "trace", "TRACE",
          "debug", "DEBUG",
          "information", "INFO",
          "warning", "WARN",
          "error", "ERROR",
          "critical", "ERROR",
          "none", "OFF");

  /** .NET log categories with an obvious Java counterpart. Others have no meaning in Java. */
  private static final Map<String, String> LOG_CATEGORIES =
      Map.of(
          "Microsoft.AspNetCore", "org.springframework.web",
          "Microsoft.EntityFrameworkCore", "org.hibernate",
          "Microsoft.EntityFrameworkCore.Database.Command", "org.hibernate.SQL",
          "Microsoft", "org.springframework");

  public GeneratedFile generate(SourceProject project) {
    List<Finding> findings = new ArrayList<>();
    Map<String, Object> yml = new LinkedHashMap<>();
    Map<String, Object> settings = new LinkedHashMap<>(project.appSettings());

    put(yml, "spring.application.name", PomGenerator.artifactId(project.name()));

    Object connectionStrings = settings.remove("ConnectionStrings");
    if (connectionStrings instanceof Map<?, ?> strings && !strings.isEmpty()) {
      boolean first = true;
      for (Map.Entry<?, ?> entry : strings.entrySet()) {
        String name = String.valueOf(entry.getKey());
        String value = String.valueOf(entry.getValue());
        boolean isDefault = name.equals("Default") || name.equals("DefaultConnection") || first;
        first = false;
        if (isDefault && !hasDatasource(yml)) {
          datasource(yml, value, name, findings);
        } else {
          put(yml, "app.connection-strings." + kebab(name), value);
        }
      }
    }
    put(yml, "spring.jpa.open-in-view", false);

    Object logging = settings.remove("Logging");
    if (logging instanceof Map<?, ?> log && log.get("LogLevel") instanceof Map<?, ?> levels) {
      for (Map.Entry<?, ?> level : levels.entrySet()) {
        String category = String.valueOf(level.getKey());
        String javaLevel = LOG_LEVELS.get(String.valueOf(level.getValue()).toLowerCase(Locale.ROOT));
        if (javaLevel == null) {
          continue;
        }
        if (category.equals("Default")) {
          putLogLevel(yml, "root", javaLevel);
        } else if (LOG_CATEGORIES.containsKey(category)) {
          putLogLevel(yml, LOG_CATEGORIES.get(category), javaLevel);
        } else {
          findings.add(
              Finding.at(
                  FindingCode.CONFIG_KEY,
                  "Log level for .NET category " + category + " was dropped: the category has no"
                      + " Java counterpart. Set logging.level for your own packages instead.",
                  SETTINGS,
                  0));
        }
      }
    }

    Object kestrel = settings.remove("Kestrel");
    Integer port = kestrelPort(kestrel);
    if (port != null) {
      put(yml, "server.port", port);
    } else if (kestrel != null) {
      findings.add(
          Finding.at(FindingCode.CONFIG_KEY, "Kestrel configuration was not translated.", SETTINGS, 0));
    }

    if (settings.remove("AllowedHosts") != null) {
      findings.add(
          Finding.at(
              FindingCode.CONFIG_KEY,
              "AllowedHosts was dropped: Spring has no host filtering; do it at the proxy.",
              SETTINGS,
              0));
    }

    if (!settings.isEmpty()) {
      Map<String, Object> app = new LinkedHashMap<>();
      settings.forEach((key, value) -> app.put(kebab(key), kebabKeys(value)));
      @SuppressWarnings("unchecked")
      Map<String, Object> existing =
          (Map<String, Object>) yml.computeIfAbsent("app", k -> new LinkedHashMap<>());
      existing.putAll(app);
    }

    return new GeneratedFile("src/main/resources/application.yml", toYaml(yml), SETTINGS, findings);
  }

  private static boolean hasDatasource(Map<String, Object> yml) {
    return yml.get("spring") instanceof Map<?, ?> spring && spring.containsKey("datasource");
  }

  /**
   * ADO.NET connection strings become a JDBC URL plus credentials. The formats differ enough that a
   * reviewer should look at the result either way.
   */
  private static void datasource(
      Map<String, Object> yml, String connectionString, String name, List<Finding> findings) {
    Map<String, String> parts = new LinkedHashMap<>();
    for (String pair : connectionString.split(";")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        parts.put(pair.substring(0, eq).strip().toLowerCase(Locale.ROOT), pair.substring(eq + 1).strip());
      }
    }
    String host = first(parts, "host", "server", "data source");
    String database = first(parts, "database", "initial catalog");
    String user = first(parts, "username", "user id", "uid", "user");
    String password = first(parts, "password", "pwd");
    String port = parts.get("port");

    String url;
    if (parts.containsKey("host")) {
      url = "jdbc:postgresql://" + host + ":" + (port == null ? "5432" : port) + "/" + database;
    } else if (host != null && database != null) {
      String server = host.replace(",", ":").replaceFirst("^tcp:", "");
      url = "jdbc:sqlserver://" + server + ";databaseName=" + database;
      if ("true".equalsIgnoreCase(parts.get("trustservercertificate"))) {
        url += ";trustServerCertificate=true";
      }
    } else {
      url = connectionString;
    }
    put(yml, "spring.datasource.url", url);
    if (user != null) {
      put(yml, "spring.datasource.username", user);
    }
    if (password != null) {
      put(yml, "spring.datasource.password", password);
    }
    findings.add(
        Finding.at(
            FindingCode.CONNECTION_STRING_FORMAT,
            "Connection string " + name + " was converted to a JDBC URL (" + url + "). ADO.NET and"
                + " JDBC options differ; review it, and move the password to an environment"
                + " variable.",
            SETTINGS,
            0));
  }

  private static String first(Map<String, String> parts, String... keys) {
    for (String key : keys) {
      if (parts.containsKey(key)) {
        return parts.get(key);
      }
    }
    return null;
  }

  private static Integer kestrelPort(Object kestrel) {
    if (!(kestrel instanceof Map<?, ?> k) || !(k.get("Endpoints") instanceof Map<?, ?> endpoints)) {
      return null;
    }
    for (Object endpoint : endpoints.values()) {
      if (endpoint instanceof Map<?, ?> e && e.get("Url") != null) {
        Matcher m = Pattern.compile(":(\\d+)").matcher(String.valueOf(e.get("Url")));
        if (m.find()) {
          return Integer.parseInt(m.group(1));
        }
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static void putLogLevel(Map<String, Object> yml, String logger, String level) {
    Map<String, Object> logging = (Map<String, Object>) yml.computeIfAbsent("logging", k -> new LinkedHashMap<>());
    Map<String, Object> levels = (Map<String, Object>) logging.computeIfAbsent("level", k -> new LinkedHashMap<>());
    levels.put(logger, level);
  }

  /** Puts a value at a dotted path, creating intermediate maps. */
  @SuppressWarnings("unchecked")
  static void put(Map<String, Object> root, String path, Object value) {
    String[] keys = path.split("\\.");
    Map<String, Object> node = root;
    for (int i = 0; i < keys.length - 1; i++) {
      node = (Map<String, Object>) node.computeIfAbsent(keys[i], k -> new LinkedHashMap<>());
    }
    node.put(keys[keys.length - 1], value);
  }

  private static Object kebabKeys(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      map.forEach((k, v) -> out.put(kebab(String.valueOf(k)), kebabKeys(v)));
      return out;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(ApplicationYmlGenerator::kebabKeys).toList();
    }
    return value;
  }

  /** {@code MaxPageSize} becomes {@code max-page-size}, Spring's canonical property form. */
  public static String kebab(String key) {
    return key.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
        .replaceAll("([A-Z])([A-Z][a-z])", "$1-$2")
        .replace('_', '-')
        .replace('.', '-')
        .toLowerCase(Locale.ROOT);
  }

  private static String toYaml(Map<String, Object> yml) {
    try {
      YAMLFactory factory =
          YAMLFactory.builder()
              .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
              .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
              .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
              .build();
      return new ObjectMapper(factory).writeValueAsString(yml);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot write application.yml", e);
    }
  }
}
