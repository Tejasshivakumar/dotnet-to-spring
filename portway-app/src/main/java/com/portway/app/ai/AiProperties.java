package com.portway.app.ai;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the LLM layer, under {@code portway.ai}. The API key is never here: it is read from
 * the environment, {@code ANTHROPIC_API_KEY} for Anthropic and {@code PORTWAY_AI_API_KEY} for every
 * other provider, so it stays out of code and config.
 *
 * @param provider {@code anthropic}, or an OpenAI-compatible endpoint: {@code gemini}, {@code
 *     groq}, {@code openrouter} and {@code ollama} come with their base URL, {@code openai}
 *     needs {@code baseUrl}
 * @param baseUrl override for the API endpoint; for Anthropic set only in tests, to point at a stub
 * @param jobTokenBudget hard stop on input plus output tokens per migration job
 * @param inputUsdPerMillion list price used for the report's cost estimate; when unset, Anthropic's
 *     list price for Anthropic and zero for the free tiers the other providers are used for
 * @param responseFormat for OpenAI-compatible providers: {@code json_schema} (structured outputs)
 *     or {@code json_object} for models that only support JSON mode
 */
@ConfigurationProperties(prefix = "portway.ai")
public record AiProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("anthropic") String provider,
    @DefaultValue("claude-opus-5") String model,
    @DefaultValue("high") String effort,
    @DefaultValue("16000") long maxTokens,
    @DefaultValue("3") int maxConcurrent,
    @DefaultValue("3") int maxRetries,
    @DefaultValue("400000") long jobTokenBudget,
    Double inputUsdPerMillion,
    Double outputUsdPerMillion,
    String baseUrl,
    @DefaultValue("json_schema") String responseFormat) {

  public boolean isAnthropic() {
    return provider == null || provider.isBlank() || provider.toLowerCase(Locale.ROOT).equals("anthropic");
  }

  public double inputPrice() {
    return inputUsdPerMillion != null ? inputUsdPerMillion : isAnthropic() ? 5.0 : 0.0;
  }

  public double outputPrice() {
    return outputUsdPerMillion != null ? outputUsdPerMillion : isAnthropic() ? 25.0 : 0.0;
  }

  /** From environment variables, for the CLI, which runs without Spring. */
  public static AiProperties fromEnvironment() {
    String provider = env("PORTWAY_AI_PROVIDER", "anthropic");
    String model = env("PORTWAY_AI_MODEL", provider.equalsIgnoreCase("anthropic") ? "claude-opus-5" : null);
    if (model == null) {
      throw new IllegalStateException("PORTWAY_AI_MODEL must be set for provider " + provider);
    }
    return new AiProperties(
        true,
        provider,
        model,
        env("PORTWAY_AI_EFFORT", "high"),
        16000,
        3,
        3,
        Long.parseLong(env("PORTWAY_AI_TOKEN_BUDGET", "400000")),
        price("PORTWAY_AI_INPUT_USD_PER_MILLION"),
        price("PORTWAY_AI_OUTPUT_USD_PER_MILLION"),
        System.getenv("PORTWAY_AI_BASE_URL"),
        env("PORTWAY_AI_RESPONSE_FORMAT", "json_schema"));
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Double price(String name) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? null : Double.valueOf(value);
  }
}
