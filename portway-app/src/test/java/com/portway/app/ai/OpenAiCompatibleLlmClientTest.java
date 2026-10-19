package com.portway.app.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.portway.core.rules.TypeMapper;
import com.portway.core.translate.Translation;
import com.portway.core.translate.TranslationRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The OpenAI-compatible client against a stubbed Chat Completions API. No real provider is called. */
class OpenAiCompatibleLlmClientTest {

  private static final String PATH = "/v1/chat/completions";

  private final ObjectMapper json = new ObjectMapper();
  private final TranslationSchema schema = new TranslationSchema();
  private WireMockServer api;

  @BeforeEach
  void start() {
    api = new WireMockServer(wireMockConfig().dynamicPort());
    api.start();
  }

  @AfterEach
  void stop() {
    api.stop();
  }

  private AiProperties properties(String provider, String baseUrl, String responseFormat) {
    return new AiProperties(true, provider, "test-model", "high", 4000, 3, 2, 100_000, null, null, baseUrl, responseFormat);
  }

  private OpenAiCompatibleLlmClient client(String responseFormat) {
    return new OpenAiCompatibleLlmClient(
        properties("gemini", api.baseUrl() + "/v1", responseFormat), schema.apiSchema(), "free-key");
  }

  private String completion(String content, String finishReason) throws Exception {
    return json.writeValueAsString(Map.of(
        "id", "chatcmpl-1",
        "object", "chat.completion",
        "choices", List.of(Map.of(
            "index", 0,
            "message", Map.of("role", "assistant", "content", content),
            "finish_reason", finishReason)),
        "usage", Map.of("prompt_tokens", 900, "completion_tokens", 120)));
  }

  private String translation() throws Exception {
    return json.writeValueAsString(Map.of(
        "javaBody", "return BigDecimal.ZERO;",
        "requiredImports", List.of("java.math.BigDecimal"),
        "notes", "Placeholder.",
        "confidence", 0.8));
  }

  private void respond(String body) {
    api.stubFor(post(urlPathEqualTo(PATH))
        .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
  }

  @Test
  void sendsAChatCompletionConstrainedToTheSchema() throws Exception {
    respond(completion(translation(), "stop"));

    LlmClient.Completion result = client("json_schema").complete("SYSTEM", "USER");

    assertThat(result.text()).contains("BigDecimal.ZERO");
    assertThat(result.inputTokens()).isEqualTo(900);
    assertThat(result.outputTokens()).isEqualTo(120);
    assertThat(result.refused()).isFalse();
    assertThat(result.truncated()).isFalse();
    api.verify(postRequestedFor(urlPathEqualTo(PATH))
        .withHeader("Authorization", equalTo("Bearer free-key"))
        .withRequestBody(matchingJsonPath("$.model", equalTo("test-model")))
        .withRequestBody(matchingJsonPath("$.temperature", equalTo("0")))
        .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
        .withRequestBody(matchingJsonPath("$.messages[0].content", containing("\"javaBody\"")))
        .withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("USER")))
        .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_schema")))
        .withRequestBody(matchingJsonPath("$.response_format.json_schema.strict", equalTo("true")))
        .withRequestBody(matchingJsonPath("$.response_format.json_schema.schema.required[0]", equalTo("javaBody"))));
  }

  @Test
  void jsonModeForModelsWithoutStructuredOutputs() throws Exception {
    respond(completion(translation(), "stop"));

    client("json_object").complete("SYSTEM", "USER");

    api.verify(postRequestedFor(urlPathEqualTo(PATH))
        .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_object")))
        .withRequestBody(matchingJsonPath("$.response_format.json_schema", absent())));
  }

  @Test
  void retriesRateLimitsThenSucceeds() throws Exception {
    api.stubFor(post(urlPathEqualTo(PATH)).inScenario("rate").whenScenarioStateIs(Scenario.STARTED)
        .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0").withBody("{\"error\":\"slow down\"}"))
        .willSetStateTo("ok"));
    api.stubFor(post(urlPathEqualTo(PATH)).inScenario("rate").whenScenarioStateIs("ok")
        .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(completion(translation(), "stop"))));

    assertThat(client("json_schema").complete("S", "U").text()).contains("javaBody");
    api.verify(2, postRequestedFor(urlPathEqualTo(PATH)));
  }

  @Test
  void givesUpAfterTheRetryLimitWithTheProviderMessage() {
    api.stubFor(post(urlPathEqualTo(PATH))
        .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "0").withBody("overloaded")));

    assertThatThrownBy(() -> client("json_schema").complete("S", "U"))
        .hasMessageContaining("HTTP 503")
        .hasMessageContaining("overloaded");
    api.verify(3, postRequestedFor(urlPathEqualTo(PATH)));
  }

  @Test
  void doesNotRetryAClientError() {
    api.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401).withBody("bad key")));

    assertThatThrownBy(() -> client("json_schema").complete("S", "U")).hasMessageContaining("HTTP 401");
    api.verify(1, postRequestedFor(urlPathEqualTo(PATH)));
  }

  @Test
  void reportsContentFilterAsRefusalAndLengthAsTruncated() throws Exception {
    respond(completion("", "content_filter"));
    assertThat(client("json_schema").complete("S", "U").refused()).isTrue();

    api.resetAll();
    respond(completion("{\"javaBody\": \"ret", "length"));
    assertThat(client("json_schema").complete("S", "U").truncated()).isTrue();
  }

  @Test
  void aTranslationArrivesThroughTheWholeTranslator() throws Exception {
    respond(completion(translation(), "stop"));
    AiProperties properties = properties("groq", api.baseUrl() + "/v1", "json_schema");
    LlmBodyTranslator translator = new LlmBodyTranslator(
        new OpenAiCompatibleLlmClient(properties, schema.apiSchema(), "k"),
        AiCache.inMemory(),
        new PromptBuilder(TypeMapper.fromDefaults()),
        schema,
        new Semaphore(1),
        new UsageMeter(properties.jobTokenBudget(), properties.inputPrice(), properties.outputPrice()));

    Optional<Translation> result = translator.translate(new TranslationRequest(
        "BookService.Total()", "BookServiceImpl", List.of(), "public BigDecimal total()",
        List.of("java.math.BigDecimal"), "public decimal Total() => 0m;", "decimal arithmetic"));

    assertThat(result).map(Translation::javaBody).contains("return BigDecimal.ZERO;");
    assertThat(translator.usage().toStats()).containsEntry("calls", 1).containsEntry("estimatedCostUsd", 0.0);
  }

  @Test
  void presetsKnowTheirBaseUrlAndOllamaNeedsNoKey() {
    assertThat(OpenAiCompatibleLlmClient.baseUrl(properties("gemini", null, "json_schema")))
        .isEqualTo("https://generativelanguage.googleapis.com/v1beta/openai");
    assertThat(OpenAiCompatibleLlmClient.baseUrl(properties("groq", "", "json_schema")))
        .isEqualTo("https://api.groq.com/openai/v1");
    assertThat(OpenAiCompatibleLlmClient.baseUrl(properties("ollama", "http://ollama:11434/v1/", "json_schema")))
        .isEqualTo("http://ollama:11434/v1");

    new OpenAiCompatibleLlmClient(properties("ollama", null, "json_schema"), schema.apiSchema(), null);
    assertThatThrownBy(() ->
            new OpenAiCompatibleLlmClient(properties("gemini", null, "json_schema"), schema.apiSchema(), null))
        .hasMessageContaining("PORTWAY_AI_API_KEY");
    assertThatThrownBy(() -> OpenAiCompatibleLlmClient.baseUrl(properties("openai", null, "json_schema")))
        .hasMessageContaining("base-url");
  }

  @Test
  void anthropicKeepsItsListPriceAndFreeProvidersCostNothing() {
    assertThat(properties("anthropic", null, "json_schema").inputPrice()).isEqualTo(5.0);
    assertThat(properties(null, null, "json_schema").outputPrice()).isEqualTo(25.0);
    assertThat(properties("gemini", null, "json_schema").inputPrice()).isZero();
  }
}
