package com.portway.app.ai;

import com.portway.core.rules.TypeMapper;
import java.util.concurrent.Semaphore;

/**
 * Makes one translator per job, so each job has its own token budget and usage figures, while the
 * model client, cache and concurrency limit are shared across jobs.
 */
public class AiTranslatorFactory {

  private final AiProperties properties;
  private final LlmClient llm;
  private final AiCache cache;
  private final PromptBuilder prompts;
  private final TranslationSchema schema;
  private final Semaphore concurrency;

  public AiTranslatorFactory(AiProperties properties, LlmClient llm, AiCache cache, TranslationSchema schema) {
    this.properties = properties;
    this.llm = llm;
    this.cache = cache;
    this.prompts = new PromptBuilder(TypeMapper.fromDefaults());
    this.schema = schema;
    this.concurrency = new Semaphore(properties.maxConcurrent());
  }

  public LlmBodyTranslator create() {
    return new LlmBodyTranslator(
        llm,
        cache,
        prompts,
        schema,
        concurrency,
        new UsageMeter(properties.jobTokenBudget(), properties.inputUsdPerMillion(), properties.outputUsdPerMillion()));
  }

  /** For the CLI: settings from the environment, a cache that lasts one run. */
  public static AiTranslatorFactory fromEnvironment() {
    AiProperties properties = AiProperties.fromEnvironment();
    TranslationSchema schema = new TranslationSchema();
    return new AiTranslatorFactory(
        properties, new AnthropicLlmClient(properties, schema.apiSchema()), AiCache.inMemory(), schema);
  }
}
