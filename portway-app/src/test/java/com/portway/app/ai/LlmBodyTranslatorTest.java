package com.portway.app.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.portway.app.TestPaths;
import com.portway.app.verify.VerifyClasspath;
import com.portway.core.MigrationSession;
import com.portway.core.generate.MigrationOptions;
import com.portway.core.report.MethodReport;
import com.portway.core.report.Strategy;
import com.portway.core.translate.Translation;
import com.portway.core.translate.TranslationRequest;
import com.portway.core.verify.InProcessCompileVerifier;
import com.portway.core.verify.VerifiedMigration;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The LLM layer against a stubbed Messages API. The real API is never called from tests.
 *
 * <p>What is under test is everything around the model: the request shape, retries, the response
 * contract, the cache, the budget, and above all what happens after a translation arrives. The
 * last two tests drive the full verify-and-repair loop with the real compiler.
 */
class LlmBodyTranslatorTest {

  private static final String TOTAL_VALUE = "BookService.TotalCatalogueValue()";
  private static final String CORRECT =
      "return bookRepository.findAll().stream()\n"
          + "    .map(b -> b.getPrice().multiply(BigDecimal.valueOf(b.getStockCount())))\n"
          + "    .reduce(BigDecimal.ZERO, BigDecimal::add);";

  private final ObjectMapper json = new ObjectMapper();
  private WireMockServer api;
  private AiCache cache;

  @BeforeEach
  void start() {
    api = new WireMockServer(wireMockConfig().dynamicPort());
    api.start();
    cache = AiCache.inMemory();
  }

  @AfterEach
  void stop() {
    api.stop();
  }

  private LlmBodyTranslator translator(long budget) {
    AiProperties properties =
        new AiProperties(true, "claude-opus-5", "high", 16000, 3, 3, budget, 5.0, 25.0, api.baseUrl());
    TranslationSchema schema = new TranslationSchema();
    return new LlmBodyTranslator(
        new AnthropicLlmClient(properties, schema.apiSchema(), "test-key"),
        cache,
        new PromptBuilder(com.portway.core.rules.TypeMapper.fromDefaults()),
        schema,
        new Semaphore(3),
        new UsageMeter(budget, 5.0, 25.0));
  }

  private String message(String text, String stopReason) throws Exception {
    return json.writeValueAsString(Map.of(
        "id", "msg_1",
        "type", "message",
        "role", "assistant",
        "model", "claude-opus-5",
        "content", List.of(Map.of("type", "text", "text", text)),
        "stop_reason", stopReason,
        "usage", Map.of("input_tokens", 1200, "output_tokens", 300)));
  }

  private String body(String javaBody) throws Exception {
    return json.writeValueAsString(Map.of(
        "javaBody", javaBody,
        "requiredImports", List.of("java.math.BigDecimal"),
        "notes", "Sums price times stock with BigDecimal arithmetic.",
        "confidence", 0.85));
  }

  private void respond(String text) throws Exception {
    api.stubFor(post(urlPathEqualTo("/v1/messages"))
        .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(message(text, "end_turn"))));
  }

  private static TranslationRequest request() {
    return new TranslationRequest(
        TOTAL_VALUE,
        "BookServiceImpl",
        List.of("private final BookRepository bookRepository"),
        "public BigDecimal totalCatalogueValue()",
        List.of("java.math.BigDecimal"),
        "public decimal TotalCatalogueValue()\n{\n    decimal total = 0;\n}",
        "does arithmetic on decimal values");
  }

  // ------------------------------------------------------------ happy path

  @Test
  void sendsTheContractedRequestAndParsesTheBody() throws Exception {
    respond(body(CORRECT));
    LlmBodyTranslator ai = translator(100_000);

    Optional<Translation> result = ai.translate(request());

    assertThat(result).get().extracting(Translation::javaBody).isEqualTo(CORRECT);
    assertThat(result.get().requiredImports()).containsExactly("java.math.BigDecimal");
    api.verify(postRequestedFor(urlPathEqualTo("/v1/messages"))
        .withHeader("x-api-key", equalTo("test-key"))
        .withHeader("anthropic-beta", containing(AnthropicLlmClient.FALLBACK_BETA))
        .withRequestBody(matchingJsonPath("$.model", equalTo("claude-opus-5")))
        .withRequestBody(matchingJsonPath("$.fallbacks", equalTo("default")))
        .withRequestBody(matchingJsonPath("$.thinking.type", equalTo("adaptive")))
        .withRequestBody(matchingJsonPath("$.output_config.format.type", equalTo("json_schema")))
        .withRequestBody(matchingJsonPath("$.system[0].cache_control.type", equalTo("ephemeral")))
        .withRequestBody(matchingJsonPath("$.system[0].text", containing("decimal -> java.math.BigDecimal")))
        .withRequestBody(matchingJsonPath("$.messages[0].content", containing("public BigDecimal totalCatalogueValue()"))));
    assertThat(ai.usage().toStats()).containsEntry("calls", 1).containsEntry("inputTokens", 1200L);
  }

  @Test
  void secondIdenticalRequestIsServedFromTheCache() throws Exception {
    respond(body(CORRECT));

    translator(100_000).translate(request());
    LlmBodyTranslator second = translator(100_000);
    Optional<Translation> cached = second.translate(request());

    assertThat(cached).isPresent();
    assertThat(api.getAllServeEvents()).hasSize(1);
    assertThat(second.usage().toStats()).containsEntry("cacheHits", 1).containsEntry("calls", 0);
  }

  // ------------------------------------------------------ contract violations

  @Test
  void malformedJsonIsRejectedNotSalvaged() throws Exception {
    respond("Here is the body: ```java\n" + CORRECT + "\n```");
    LlmBodyTranslator ai = translator(100_000);

    assertThat(ai.translate(request())).isEmpty();
    assertThat(ai.usage().toStats()).containsEntry("rejectedResponses", 1);
  }

  @Test
  void schemaViolationIsRejected() throws Exception {
    respond(json.writeValueAsString(Map.of("javaBody", CORRECT, "confidence", 3)));

    assertThat(translator(100_000).translate(request())).isEmpty();
  }

  @Test
  void aSignatureOrFencesInsideTheBodyAreRejected() throws Exception {
    TranslationSchema schema = new TranslationSchema();
    for (String bad : List.of(
        "public BigDecimal totalCatalogueValue() { return null; }",
        "```java\nreturn null;\n```",
        "class Helper { }\nreturn null;")) {
      respond(body(bad));
      assertThat(translator(100_000).translate(request())).describedAs(bad).isEmpty();
      cache = AiCache.inMemory();
    }
    assertThat(schema.parse(body(CORRECT)).javaBody()).isEqualTo(CORRECT);
  }

  @Test
  void refusalAndTruncationAreNotTranslations() throws Exception {
    api.stubFor(post(urlPathEqualTo("/v1/messages"))
        .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(message("", "refusal"))));
    LlmBodyTranslator ai = translator(100_000);
    assertThat(ai.translate(request())).isEmpty();
    assertThat(ai.usage().toStats()).containsEntry("refusals", 1);

    api.resetAll();
    api.stubFor(post(urlPathEqualTo("/v1/messages"))
        .willReturn(aResponse().withHeader("Content-Type", "application/json")
            .withBody(message("{\"javaBody\": \"return", "max_tokens"))));
    assertThat(translator(100_000).translate(request())).isEmpty();
  }

  // ------------------------------------------------------- limits and retries

  @Test
  void rateLimitIsRetriedWithBackoff() throws Exception {
    api.stubFor(post(urlPathEqualTo("/v1/messages")).inScenario("429")
        .whenScenarioStateIs(Scenario.STARTED)
        .willReturn(aResponse().withStatus(429).withHeader("retry-after", "0")
            .withBody("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}"))
        .willSetStateTo("recovered"));
    api.stubFor(post(urlPathEqualTo("/v1/messages")).inScenario("429")
        .whenScenarioStateIs("recovered")
        .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(message(body(CORRECT), "end_turn"))));

    assertThat(translator(100_000).translate(request())).isPresent();
    assertThat(api.getAllServeEvents()).hasSize(2);
  }

  @Test
  void persistentServerErrorsFailTheMethodNotTheJob() throws Exception {
    api.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(aResponse().withStatus(529)
        .withHeader("retry-after", "0")
        .withBody("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}")));

    assertThat(translator(100_000).translate(request())).isEmpty();
    // One attempt plus three retries.
    assertThat(api.getAllServeEvents()).hasSize(4);
  }

  @Test
  void tokenBudgetIsAHardStop() throws Exception {
    respond(body(CORRECT));
    LlmBodyTranslator ai = translator(1000);

    assertThat(ai.translate(request())).isPresent();
    TranslationRequest another = new TranslationRequest("X.Y()", "X", List.of(), "void y()", List.of(), "void Y() {}", null);
    assertThat(ai.translate(another)).isEmpty();
    assertThat(api.getAllServeEvents()).hasSize(1);
    assertThat(ai.usage().toStats()).containsEntry("skippedOverBudget", 1);
  }

  // ------------------------------------------------ the loop, with the compiler

  @Test
  void brokenTranslationRepairedOnceIsAiRepaired(@TempDir Path work) throws Exception {
    api.stubFor(post(urlPathEqualTo("/v1/messages")).inScenario("repair")
        .whenScenarioStateIs(Scenario.STARTED)
        .willReturn(aResponse().withHeader("Content-Type", "application/json")
            .withBody(message(body("return total;"), "end_turn")))
        .willSetStateTo("second"));
    api.stubFor(post(urlPathEqualTo("/v1/messages")).inScenario("repair")
        .whenScenarioStateIs("second")
        .withRequestBody(containing("did not compile"))
        .willReturn(aResponse().withHeader("Content-Type", "application/json")
            .withBody(message(body(CORRECT), "end_turn"))));

    VerifiedMigration.Outcome outcome = migrate(translator(100_000), work);

    assertThat(outcome.compiles()).isTrue();
    assertThat(method(outcome).strategy()).isEqualTo(Strategy.AI_REPAIRED);
  }

  @Test
  void translationThatNeverCompilesIsDemotedToManual(@TempDir Path work) throws Exception {
    respond(body("return impossible.value();"));

    VerifiedMigration.Outcome outcome = migrate(translator(100_000), work);

    assertThat(outcome.compiles()).isTrue();
    MethodReport method = method(outcome);
    assertThat(method.strategy()).isEqualTo(Strategy.MANUAL_REQUIRED);
    assertThat(method.reason()).contains("did not compile");
    // One translation, one repair: never a third attempt.
    assertThat(api.getAllServeEvents()).hasSize(2);
  }

  private VerifiedMigration.Outcome migrate(LlmBodyTranslator ai, Path work) {
    MigrationSession session = new MigrationSession(TestPaths.sample(), MigrationOptions.defaults());
    session.run();
    return new VerifiedMigration(new InProcessCompileVerifier(VerifyClasspath.resolve()), ai)
        .run(session, work, VerifiedMigration.Listener.NONE);
  }

  private static MethodReport method(VerifiedMigration.Outcome outcome) {
    return outcome.result().method(TOTAL_VALUE);
  }
}
