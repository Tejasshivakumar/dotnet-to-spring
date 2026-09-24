package com.portway.app.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portway.app.config.PortwayProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipIngestTest {

  private static final PortwayProperties LIMITS =
      new PortwayProperties(null, new PortwayProperties.Upload(10, 1000, 400), null, null);

  private final ZipIngest ingest = new ZipIngest(LIMITS);

  @Test
  void extractsSourcesAndFindsTheProjectRootInsideAWrapperFolder(@TempDir Path target) throws IOException {
    byte[] zip = zip(Map.of(
        "wrapper/Api/Api.csproj", "<Project/>",
        "wrapper/Api/Models/Book.cs", "class Book {}",
        "wrapper/Api/appsettings.json", "{}",
        "wrapper/Api/appsettings.Development.json", "{}",
        "wrapper/Api/README.md", "ignored",
        "wrapper/Api/bin/Debug/Api.dll", "ignored",
        "wrapper/Api/obj/Api.AssemblyInfo.cs", "ignored"));

    ZipIngest.Extracted extracted = ingest.extract(new ByteArrayInputStream(zip), target);

    assertThat(extracted.root()).isEqualTo(target.resolve("wrapper/Api"));
    assertThat(extracted.files()).isEqualTo(4);
    assertThat(target.resolve("wrapper/Api/Models/Book.cs")).exists();
    assertThat(target.resolve("wrapper/Api/README.md")).doesNotExist();
    assertThat(target.resolve("wrapper/Api/obj")).doesNotExist();
  }

  @Test
  void refusesZipSlip(@TempDir Path target) throws IOException {
    byte[] zip = zip(Map.of("../../escape.cs", "class Evil {}"));

    assertThatThrownBy(() -> ingest.extract(new ByteArrayInputStream(zip), target.resolve("jobs/x")))
        .isInstanceOf(UploadRejectedException.class)
        .hasMessageContaining("escapes");
    assertThat(target.resolve("escape.cs")).doesNotExist();
  }

  @Test
  void refusesTooManyEntries(@TempDir Path target) throws IOException {
    Map<String, String> many = new java.util.LinkedHashMap<>();
    for (int i = 0; i < 11; i++) {
      many.put("F" + i + ".cs", "class F" + i + " {}");
    }

    assertThatThrownBy(() -> ingest.extract(new ByteArrayInputStream(zip(many)), target))
        .isInstanceOf(UploadRejectedException.class)
        .hasMessageContaining("more than 10 entries");
  }

  @Test
  void countsInflatedBytesNotDeclaredOnes(@TempDir Path target) throws IOException {
    // 500 bytes of zeros compresses to almost nothing: the per-entry cap must trip
    // on what actually comes out of the inflater.
    byte[] zip = zip(Map.of("Big.cs", "0".repeat(500)));

    assertThatThrownBy(() -> ingest.extract(new ByteArrayInputStream(zip), target))
        .isInstanceOf(UploadRejectedException.class)
        .hasMessageContaining("Big.cs");
  }

  @Test
  void capsTheTotalAcrossEntries(@TempDir Path target) throws IOException {
    Map<String, String> files = new java.util.LinkedHashMap<>();
    for (int i = 0; i < 4; i++) {
      files.put("F" + i + ".cs", "x".repeat(300));
    }

    assertThatThrownBy(() -> ingest.extract(new ByteArrayInputStream(zip(files)), target))
        .isInstanceOf(UploadRejectedException.class)
        .hasMessageContaining("inflates to more than");
  }

  @Test
  void refusesSomethingThatIsNotAZipOrHasNoSources(@TempDir Path target) throws IOException {
    assertThatThrownBy(() -> ingest.extract(new ByteArrayInputStream("not a zip".getBytes()), target))
        .isInstanceOf(UploadRejectedException.class);
    assertThatThrownBy(() -> ingest.extract(new ByteArrayInputStream(zip(Map.of("a.txt", "x"))), target))
        .isInstanceOf(UploadRejectedException.class)
        .hasMessageContaining("no C# sources");
  }

  static byte[] zip(Map<String, String> entries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, String> e : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey()));
        zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }
}
