package com.portway.core.ir;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourceProjectTest {

  /**
   * appsettings.json is parsed straight into a Map and JSON nulls are legal there. Map.copyOf
   * rejects null values outright, which would blow up on a perfectly ordinary config file.
   */
  @Test
  void toleratesNullValuesInAppSettings() {
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("ConnectionStrings", Map.of("Default", "Host=localhost"));
    settings.put("OptionalFeature", null);

    SourceProject project = new SourceProject("Api", "net8.0", List.of(), List.of(), settings);

    assertThat(project.appSettings()).containsKey("OptionalFeature");
    assertThat(project.appSettings().get("OptionalFeature")).isNull();
  }

  @Test
  void preservesAppSettingsKeyOrder() {
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("ConnectionStrings", "a");
    settings.put("Logging", "b");
    settings.put("Kestrel", "c");
    settings.put("Bookstore", "d");

    SourceProject project = new SourceProject("Api", "net8.0", List.of(), List.of(), settings);

    assertThat(project.appSettings().keySet())
        .containsExactly("ConnectionStrings", "Logging", "Kestrel", "Bookstore");
  }

  @Test
  void handlesNullCollectionsAsEmpty() {
    SourceProject project = new SourceProject("Api", "net8.0", null, null, null);

    assertThat(project.packages()).isEmpty();
    assertThat(project.files()).isEmpty();
    assertThat(project.appSettings()).isEmpty();
    assertThat(project.allTypes()).isEmpty();
  }
}
