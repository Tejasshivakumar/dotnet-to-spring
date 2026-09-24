package com.portway.app.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A job with AI switched on, end to end, against a stubbed model: the one method the rules route to
 * the model comes back translated, compiles, is recorded as AI_VERIFIED, and its token use lands
 * in the report. The second run is served entirely from the cache.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class AiPipelineIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  static final WireMockServer api = new WireMockServer(wireMockConfig().dynamicPort());

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws IOException {
    api.start();
    registry.add("portway.work-dir", () -> tempDir());
    registry.add("portway.ai.enabled", () -> "true");
    registry.add("portway.ai.base-url", api::baseUrl);
    // The SDK reads its key from the environment or this system property; the stub ignores it.
    System.setProperty("anthropic.apiKey", "test-key");
  }

  @AfterAll
  static void stop() {
    api.stop();
  }

  @Autowired TestRestTemplate http;

  @Test
  void methodTheRulesCannotHandleIsTranslatedVerifiedAndCached() throws Exception {
    ObjectMapper json = new ObjectMapper();
    String body = json.writeValueAsString(Map.of(
        "javaBody", "return bookRepository.findAll().stream()\n"
            + "    .map(b -> b.getPrice().multiply(BigDecimal.valueOf(b.getStockCount())))\n"
            + "    .reduce(BigDecimal.ZERO, BigDecimal::add);",
        "requiredImports", List.of("java.math.BigDecimal"),
        "notes", "BigDecimal arithmetic.",
        "confidence", 0.9));
    api.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(aResponse()
        .withHeader("Content-Type", "application/json")
        .withBody(json.writeValueAsString(Map.of(
            "id", "msg_1", "type", "message", "role", "assistant", "model", "claude-opus-5",
            "content", List.of(Map.of("type", "text", "text", body)),
            "stop_reason", "end_turn",
            "usage", Map.of("input_tokens", 2000, "output_tokens", 400))))));

    JsonNode first = run();
    assertThat(first.at("/job/compileStatus").asText()).isEqualTo("PASS");
    assertThat(first.at("/stats/methodsByStrategy/AI_VERIFIED").asInt()).isEqualTo(1);
    assertThat(first.at("/stats/methodsByStrategy/MANUAL_REQUIRED").asInt()).describedAs("yield stays manual").isEqualTo(1);
    assertThat(first.at("/stats/ai/calls").asInt()).isEqualTo(1);
    assertThat(first.at("/stats/ai/inputTokens").asLong()).isEqualTo(2000);
    assertThat(first.at("/stats/ai/estimatedCostUsd").asDouble()).isEqualTo(0.02);

    JsonNode second = run();
    assertThat(second.at("/stats/methodsByStrategy/AI_VERIFIED").asInt()).isEqualTo(1);
    assertThat(second.at("/stats/ai/calls").asInt()).isZero();
    assertThat(second.at("/stats/ai/cacheHits").asInt()).isEqualTo(1);
    assertThat(api.getAllServeEvents()).hasSize(1);
  }

  private JsonNode run() {
    var created = http.postForEntity("/api/jobs/sample?ai=true", null, JsonNode.class);
    assertThat(created.getStatusCode().value()).describedAs(String.valueOf(created.getBody())).isEqualTo(202);
    String id = created.getBody().get("id").asText();
    await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(250)).until(() -> {
      String status = http.getForObject("/api/jobs/" + id, JsonNode.class).at("/job/status").asText();
      assertThat(status).isNotEqualTo("FAILED");
      return status.equals("COMPLETED");
    });
    return http.getForObject("/api/jobs/" + id, JsonNode.class);
  }

  private static String tempDir() {
    try {
      return Files.createTempDirectory("portway-ai-it").toString();
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
