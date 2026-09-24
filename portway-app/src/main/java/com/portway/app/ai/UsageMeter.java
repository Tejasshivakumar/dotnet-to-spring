package com.portway.app.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token use for one job, and the budget that stops it.
 *
 * <p>The budget is a hard stop: once spent, further methods are left to the rules and to humans,
 * and the report says how many were skipped. A migration never costs more than it was allowed to.
 */
public class UsageMeter {

  private final long budget;
  private final double inputUsdPerMillion;
  private final double outputUsdPerMillion;
  private final AtomicLong inputTokens = new AtomicLong();
  private final AtomicLong outputTokens = new AtomicLong();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicInteger cacheHits = new AtomicInteger();
  private final AtomicInteger rejected = new AtomicInteger();
  private final AtomicInteger refused = new AtomicInteger();
  private final AtomicInteger overBudget = new AtomicInteger();

  public UsageMeter(long budget, double inputUsdPerMillion, double outputUsdPerMillion) {
    this.budget = budget;
    this.inputUsdPerMillion = inputUsdPerMillion;
    this.outputUsdPerMillion = outputUsdPerMillion;
  }

  public boolean exhausted() {
    return inputTokens.get() + outputTokens.get() >= budget;
  }

  public void call(long in, long out) {
    calls.incrementAndGet();
    inputTokens.addAndGet(in);
    outputTokens.addAndGet(out);
  }

  public void cacheHit() {
    cacheHits.incrementAndGet();
  }

  public void rejected() {
    rejected.incrementAndGet();
  }

  public void refused() {
    refused.incrementAndGet();
  }

  public void overBudget() {
    overBudget.incrementAndGet();
  }

  public long totalTokens() {
    return inputTokens.get() + outputTokens.get();
  }

  public Map<String, Object> toStats() {
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("calls", calls.get());
    stats.put("cacheHits", cacheHits.get());
    stats.put("rejectedResponses", rejected.get());
    stats.put("refusals", refused.get());
    stats.put("skippedOverBudget", overBudget.get());
    stats.put("inputTokens", inputTokens.get());
    stats.put("outputTokens", outputTokens.get());
    stats.put("tokenBudget", budget);
    stats.put("estimatedCostUsd",
        Math.round((inputTokens.get() * inputUsdPerMillion + outputTokens.get() * outputUsdPerMillion) / 100.0) / 10_000.0);
    return stats;
  }
}
