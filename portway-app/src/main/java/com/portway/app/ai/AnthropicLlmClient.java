package com.portway.app.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaJsonOutputFormat;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive;
import com.anthropic.models.beta.messages.BetaThinkingConfigParam;
import com.anthropic.models.beta.messages.MessageCreateParams;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The Anthropic Messages API, through the official Java SDK.
 *
 * <ul>
 *   <li>The response is constrained to the translation JSON schema with structured outputs. It is
 *       validated again locally all the same: the model is checked, never trusted.
 *   <li>The system prompt is identical for every method in every job, so it is marked for prompt
 *       caching.
 *   <li>429s and 5xx responses are retried by the SDK with exponential backoff, up to {@code
 *       maxRetries} times.
 *   <li>If the model's safety classifiers decline a request, the server-side fallback re-runs it
 *       on the model Anthropic recommends for that category rather than returning a refusal.
 * </ul>
 */
public class AnthropicLlmClient implements LlmClient {

  static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

  private final AnthropicClient client;
  private final AiProperties properties;
  private final Map<String, JsonValue> schema;

  public AnthropicLlmClient(AiProperties properties, Map<String, Object> apiSchema) {
    this(properties, apiSchema, null);
  }

  /** @param testApiKey for tests against a stub server only; production reads the environment */
  AnthropicLlmClient(AiProperties properties, Map<String, Object> apiSchema, String testApiKey) {
    this.properties = properties;
    AnthropicOkHttpClient.Builder builder =
        AnthropicOkHttpClient.builder().fromEnv().maxRetries(properties.maxRetries()).timeout(Duration.ofMinutes(5));
    if (testApiKey != null) {
      builder.apiKey(testApiKey);
    }
    if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
      builder.baseUrl(properties.baseUrl());
    }
    this.client = builder.build();
    this.schema =
        apiSchema.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> JsonValue.from(e.getValue())));
  }

  @Override
  public String model() {
    return properties.model();
  }

  @Override
  public Completion complete(String systemPrompt, String userMessage) {
    BetaJsonOutputFormat.Schema.Builder schemaBuilder = BetaJsonOutputFormat.Schema.builder();
    schema.forEach(schemaBuilder::putAdditionalProperty);

    MessageCreateParams params =
        MessageCreateParams.builder()
            .model(properties.model())
            .maxTokens(properties.maxTokens())
            .systemOfBetaTextBlockParams(
                List.of(
                    BetaTextBlockParam.builder()
                        .text(systemPrompt)
                        .cacheControl(BetaCacheControlEphemeral.builder().build())
                        .build()))
            .thinking(BetaThinkingConfigParam.ofAdaptive(BetaThinkingConfigAdaptive.builder().build()))
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.of(properties.effort().toLowerCase(Locale.ROOT)))
                    .format(BetaJsonOutputFormat.builder().schema(schemaBuilder.build()).build())
                    .build())
            .addBeta(FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .addUserMessage(userMessage)
            .build();

    BetaMessage response = client.beta().messages().create(params);
    long in = response.usage().inputTokens();
    long out = response.usage().outputTokens();
    BetaStopReason stop = response.stopReason().orElse(null);
    if (BetaStopReason.REFUSAL.equals(stop)) {
      return new Completion(null, in, out, true, false);
    }
    String text =
        response.content().stream()
            .map(BetaContentBlock::text)
            .flatMap(java.util.Optional::stream)
            .map(t -> t.text())
            .collect(Collectors.joining());
    return new Completion(text, in, out, false, BetaStopReason.MAX_TOKENS.equals(stop));
  }
}
