package com.portway.app.ai;

import com.portway.core.translate.BodyTranslator;
import com.portway.core.translate.Translation;
import com.portway.core.translate.TranslationRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Translates method bodies with the LLM, for one job.
 *
 * <p>Every call goes through the same gates, in order: the job's token budget, the shared cache,
 * the concurrency limit, the model, then the response contract. A response that breaks the
 * contract is discarded, never repaired by hand here; the method stays a stub and the reviewer
 * sees why. Whatever survives still has to compile before it is kept, which is the loop's job, not
 * this class's.
 */
public class LlmBodyTranslator implements BodyTranslator {

  private static final Logger log = LoggerFactory.getLogger(LlmBodyTranslator.class);

  private final LlmClient llm;
  private final AiCache cache;
  private final PromptBuilder prompts;
  private final TranslationSchema schema;
  private final Semaphore concurrency;
  private final UsageMeter usage;

  public LlmBodyTranslator(
      LlmClient llm, AiCache cache, PromptBuilder prompts, TranslationSchema schema, Semaphore concurrency, UsageMeter usage) {
    this.llm = llm;
    this.cache = cache;
    this.prompts = prompts;
    this.schema = schema;
    this.concurrency = concurrency;
    this.usage = usage;
  }

  public UsageMeter usage() {
    return usage;
  }

  @Override
  public Optional<Translation> translate(TranslationRequest request) {
    return call(request.methodKey(), prompts.user(request));
  }

  @Override
  public Optional<Translation> repair(TranslationRequest request, String previousBody, String compilerErrors) {
    return call(request.methodKey(), prompts.repair(request, previousBody, compilerErrors));
  }

  /** In parallel, bounded by the shared concurrency limit rather than by this pool's size. */
  @Override
  public Map<String, Translation> translateAll(List<TranslationRequest> requests) {
    Map<String, Translation> results = new LinkedHashMap<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      Map<String, CompletableFuture<Optional<Translation>>> futures = new LinkedHashMap<>();
      for (TranslationRequest request : requests) {
        futures.put(request.methodKey(), CompletableFuture.supplyAsync(() -> translate(request), pool));
      }
      futures.forEach((key, future) -> future.join().ifPresent(t -> results.put(key, t)));
    }
    return results;
  }

  private Optional<Translation> call(String methodKey, String userMessage) {
    String key = AiCache.key(llm.model(), prompts.system(), userMessage);
    Optional<AiCache.Entry> cached = cache.get(key);
    if (cached.isPresent()) {
      usage.cacheHit();
      return Optional.of(schema.parse(cached.get().responseJson()));
    }
    if (usage.exhausted()) {
      usage.overBudget();
      log.info("Token budget spent; leaving {} to manual review", methodKey);
      return Optional.empty();
    }

    LlmClient.Completion completion;
    try {
      concurrency.acquire();
      try {
        completion = llm.complete(prompts.system(), userMessage);
      } finally {
        concurrency.release();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (RuntimeException e) {
      // Retries already happened inside the SDK. One failed method must not fail the job.
      log.warn("Model call for {} failed: {}", methodKey, e.toString());
      return Optional.empty();
    }
    usage.call(completion.inputTokens(), completion.outputTokens());

    if (completion.refused()) {
      usage.refused();
      log.info("Model declined {}", methodKey);
      return Optional.empty();
    }
    if (completion.truncated()) {
      usage.rejected();
      log.info("Response for {} hit the token limit and was discarded", methodKey);
      return Optional.empty();
    }
    try {
      Translation translation = schema.parse(completion.text());
      cache.put(key, llm.model(), new AiCache.Entry(completion.text(), completion.inputTokens(), completion.outputTokens()));
      return Optional.of(translation);
    } catch (TranslationSchema.InvalidResponse e) {
      usage.rejected();
      log.info("Response for {} rejected: {}", methodKey, e.getMessage());
      return Optional.empty();
    }
  }
}
