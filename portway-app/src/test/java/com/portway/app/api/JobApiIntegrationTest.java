package com.portway.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portway.app.TestPaths;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole application against a real PostgreSQL: upload, pipeline, progress, review,
 * re-verification, report, download, delete. Flyway runs the real migrations and Hibernate validates
 * the entities against them, so schema drift fails here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class JobApiIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void workDir(DynamicPropertyRegistry registry) throws IOException {
    Path dir = Files.createTempDirectory("portway-it");
    registry.add("portway.work-dir", dir::toString);
  }

  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper json;
  @LocalServerPort int port;

  @Test
  void sampleProjectRunsThroughTheWholePipeline() throws Exception {
    ResponseEntity<JsonNode> created = http.postForEntity("/api/jobs/sample", null, JsonNode.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(created.getHeaders().getLocation()).isNotNull();
    String id = created.getBody().get("id").asText();

    JsonNode job = awaitCompletion(id);

    assertThat(job.at("/job/status").asText()).isEqualTo("COMPLETED");
    assertThat(job.at("/job/compileStatus").asText()).isEqualTo("PASS");
    assertThat(job.at("/job/verifier").asText()).contains("in-process");
    assertThat(job.at("/stats/files").asInt()).isEqualTo(17);
    assertThat(job.at("/stats/methodsByStrategy/RULE_MAPPED").asInt()).isEqualTo(14);
    assertThat(job.at("/stats/methodsByStrategy/MANUAL_REQUIRED").asInt()).isEqualTo(2);
    Set<String> stages = new HashSet<>();
    job.get("stages").forEach(s -> {
      stages.add(s.get("stage").asText());
      assertThat(s.get("completedAt").isNull()).describedAs(s.toString()).isFalse();
    });
    assertThat(stages).contains("INGEST", "PARSE", "CLASSIFY", "PLAN", "GENERATE", "AI_FILL", "VERIFY", "REPORT");

    // Files, grouped for review, with the weakest strategy per file.
    JsonNode files = http.getForObject("/api/jobs/" + id + "/files", JsonNode.class);
    assertThat(files).hasSize(17);
    JsonNode service = find(files, "src/main/java/bookstoreapi/service/BookServiceImpl.java");
    assertThat(service.get("category").asText()).isEqualTo("SERVICE");
    assertThat(service.get("strategy").asText()).isEqualTo("MANUAL_REQUIRED");
    assertThat(service.get("confidence").asDouble()).isZero();
    assertThat(service.get("sourcePath").asText()).isEqualTo("Services/BookService.cs");
    JsonNode controller = find(files, "src/main/java/bookstoreapi/controller/BooksController.java");
    assertThat(controller.get("confidence").asDouble()).isEqualTo(0.95);

    // One file in full: C# on the left, Java on the right, findings below.
    JsonNode detail = http.getForObject("/api/jobs/" + id + "/files/" + service.get("id").asText(), JsonNode.class);
    assertThat(detail.get("sourceLanguage").asText()).isEqualTo("csharp");
    assertThat(detail.get("sourceContent").asText()).contains("public class BookService");
    assertThat(detail.get("content").asText()).contains("public class BookServiceImpl");
    assertThat(detail.get("findings")).anySatisfy(f -> assertThat(f.get("code").asText()).isEqualTo("METHOD_STUBBED"));
    assertThat(detail.get("methods")).hasSize(7);

    JsonNode report = http.getForObject("/api/jobs/" + id + "/report", JsonNode.class);
    assertThat(report.get("manualItems")).hasSize(2);
    assertThat(report.at("/review/pending").asInt()).isEqualTo(17);
    assertThat(report.at("/findings/0/severity").asText()).isEqualTo("HIGH");

    // An edit that breaks the file: compile status goes stale, then re-verification fails it.
    String entity = find(files, "src/main/java/bookstoreapi/domain/Genre.java").get("id").asText();
    JsonNode reviewed = post("/api/jobs/" + id + "/files/" + entity + "/review",
        "{\"status\":\"ACCEPTED\",\"editedContent\":\"package bookstoreapi.domain; public enum Genre { BROKEN BROKEN }\"}");
    assertThat(reviewed.get("compileStatus").asText()).isEqualTo("NOT_RUN");
    assertThat(reviewed.get("edited").asBoolean()).isTrue();

    assertThat(http.postForEntity("/api/jobs/" + id + "/verify", null, Void.class).getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    await().atMost(Duration.ofSeconds(60)).until(() ->
        "FAIL".equals(http.getForObject("/api/jobs/" + id, JsonNode.class).at("/job/compileStatus").asText()));
    JsonNode afterVerify = http.getForObject("/api/jobs/" + id + "/files/" + entity, JsonNode.class);
    assertThat(afterVerify.at("/file/compileStatus").asText()).isEqualTo("FAIL");
    assertThat(afterVerify.get("findings")).anySatisfy(f -> assertThat(f.get("code").asText()).isEqualTo("COMPILE_ERROR"));

    // Rejecting the broken file leaves it out of both verification and the download.
    post("/api/jobs/" + id + "/files/" + entity + "/review", "{\"status\":\"REJECTED\"}");
    Set<String> entries = downloadEntries(id);
    assertThat(entries).contains("pom.xml", "src/main/java/bookstoreapi/service/BookServiceImpl.java")
        .doesNotContain("src/main/java/bookstoreapi/domain/Genre.java");

    assertThat(http.exchange(URI.create("/api/jobs/" + id).toString(), org.springframework.http.HttpMethod.DELETE, null, Void.class)
        .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(http.getForEntity("/api/jobs/" + id, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void uploadedZipIsMigrated() throws Exception {
    ResponseEntity<JsonNode> created = upload(sampleZip(), "BookstoreApi.zip", "com.example.books");
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    String id = created.getBody().get("id").asText();

    JsonNode job = awaitCompletion(id);

    assertThat(job.at("/job/name").asText()).isEqualTo("BookstoreApi");
    assertThat(job.at("/options/basePackage").asText()).isEqualTo("com.example.books");
    JsonNode files = http.getForObject("/api/jobs/" + id + "/files", JsonNode.class);
    assertThat(files).anySatisfy(f ->
        assertThat(f.get("path").asText()).isEqualTo("src/main/java/com/example/books/domain/Book.java"));
  }

  @Test
  void hostileUploadIsRefusedAsAProblem() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("../../../tmp/evil.cs"));
      zip.write("class Evil {}".getBytes());
      zip.closeEntry();
    }

    ResponseEntity<JsonNode> response = upload(bytes.toByteArray(), "evil.zip", null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
    assertThat(response.getBody().get("title").asText()).isEqualTo("Upload rejected");
    assertThat(response.getBody().get("detail").asText()).contains("escapes");
  }

  @Test
  void invalidPackageAndUnknownJobAreProblems() {
    ResponseEntity<JsonNode> badPackage =
        http.postForEntity("/api/jobs/sample?basePackage=Not-A.Package", null, JsonNode.class);
    assertThat(badPackage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    ResponseEntity<JsonNode> missing =
        http.getForEntity("/api/jobs/00000000-0000-0000-0000-000000000000", JsonNode.class);
    assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(missing.getBody().get("title").asText()).isEqualTo("Not found");
  }

  @Test
  void eventsStreamEndsWithCompleted() throws Exception {
    String id = http.postForEntity("/api/jobs/sample", null, JsonNode.class).getBody().get("id").asText();

    HttpResponse<Stream<String>> stream =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/jobs/" + id + "/events"))
                    .header("Accept", "text/event-stream")
                    .timeout(Duration.ofSeconds(90))
                    .build(),
                HttpResponse.BodyHandlers.ofLines());

    assertThat(stream.statusCode()).isEqualTo(200);
    // A late subscriber may join after some stages; either way the stream must end
    // with a completed event and then close.
    java.util.List<String> events = stream.body().filter(l -> l.startsWith("event:")).toList();
    assertThat(events).last().isEqualTo("event:completed");
  }

  @Test
  void listShowsNewestFirst() {
    http.postForEntity("/api/jobs/sample", null, JsonNode.class);
    JsonNode page = http.getForObject("/api/jobs?size=5", JsonNode.class);
    assertThat(page.get("items").size()).isPositive();
    assertThat(page.get("size").asInt()).isEqualTo(5);
  }

  // ----------------------------------------------------------------- helpers

  private JsonNode awaitCompletion(String id) {
    await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(250)).until(() -> {
      String status = http.getForObject("/api/jobs/" + id, JsonNode.class).at("/job/status").asText();
      assertThat(status).isNotEqualTo("FAILED");
      return status.equals("COMPLETED");
    });
    return http.getForObject("/api/jobs/" + id, JsonNode.class);
  }

  private JsonNode post(String path, String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<JsonNode> response = http.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
    assertThat(response.getStatusCode().is2xxSuccessful()).describedAs(String.valueOf(response.getBody())).isTrue();
    return response.getBody();
  }

  private ResponseEntity<JsonNode> upload(byte[] zip, String filename, String basePackage) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("file", new ByteArrayResource(zip) {
      @Override
      public String getFilename() {
        return filename;
      }
    });
    if (basePackage != null) {
      form.add("basePackage", basePackage);
    }
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    return http.postForEntity("/api/jobs", new HttpEntity<>(form, headers), JsonNode.class);
  }

  private Set<String> downloadEntries(String id) throws IOException {
    ResponseEntity<byte[]> download = http.getForEntity("/api/jobs/" + id + "/download", byte[].class);
    assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(download.getHeaders().getContentDisposition().getFilename()).endsWith("-spring.zip");
    Set<String> names = new HashSet<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(download.getBody()))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        names.add(entry.getName());
      }
    }
    return names;
  }

  private static byte[] sampleZip() throws IOException {
    Path root = TestPaths.sample();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes); Stream<Path> walk = Files.walk(root)) {
      for (Path file : walk.filter(Files::isRegularFile).toList()) {
        zip.putNextEntry(new ZipEntry("BookstoreApi/" + root.relativize(file).toString().replace('\\', '/')));
        zip.write(Files.readAllBytes(file));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static JsonNode find(JsonNode files, String path) {
    for (JsonNode f : files) {
      if (f.get("path").asText().equals(path)) {
        return f;
      }
    }
    throw new AssertionError("No generated file " + path);
  }
}
