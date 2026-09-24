package com.portway.app.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the LLM layer, under {@code portway.ai}. The API key is never here: the SDK reads
 * it from the {@code ANTHROPIC_API_KEY} environment variable, so it stays out of code and config.
 *
 * @param baseUrl override for the API endpoint; set only in tests, to point at a stub server
 * @param jobTokenBudget hard stop on input plus output tokens per migration job
 * @param inputUsdPerMillion list price used for the report's cost estimate
 */
@ConfigurationProperties(prefix = "portway.ai")
public record AiProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("claude-opus-5") String model,
    @DefaultValue("high") String effort,
    @DefaultValue("16000") long maxTokens,
    @DefaultValue("3") int maxConcurrent,
    @DefaultValue("3") int maxRetries,
    @DefaultValue("400000") long jobTokenBudget,
    @DefaultValue("5.0") double inputUsdPerMillion,
    @DefaultValue("25.0") double outputUsdPerMillion,
    String baseUrl) {

  /** From environment variables, for the CLI, which runs without Spring. */
  public static AiProperties fromEnvironment() {
    String model = System.getenv().getOrDefault("PORTWAY_AI_MODEL", "claude-opus-5");
    String effort = System.getenv().getOrDefault("PORTWAY_AI_EFFORT", "high");
    return new AiProperties(true, model, effort, 16000, 3, 3, 400_000, 5.0, 25.0, System.getenv("PORTWAY_AI_BASE_URL"));
  }
}
