package com.portway.app.ai;

/**
 * One request to the model: a system prompt and a user message in, a JSON document out.
 *
 * <p>Narrow on purpose. Everything above it (caching, budgets, validation, repair) is testable
 * against a stub, and the SDK-specific code stays in one class.
 */
public interface LlmClient {

  /**
   * @param text the model's JSON output, or null when it declined
   * @param refused true when the model's safety classifiers declined the request
   * @param truncated true when output hit the token limit and cannot be trusted
   */
  record Completion(String text, long inputTokens, long outputTokens, boolean refused, boolean truncated) {}

  Completion complete(String systemPrompt, String userMessage);

  /** The model id, part of the cache key: a cached answer from another model is not this one's. */
  String model();
}
