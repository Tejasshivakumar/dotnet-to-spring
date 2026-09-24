package com.portway.core.report;

/**
 * How a piece of generated code was produced, and so how far it can be trusted.
 *
 * <p>A file's confidence is the <em>minimum</em> over its members, not the average. A file with one
 * stubbed method is not 90% done, and an average would let that method hide.
 */
public enum Strategy {
  /** Fully deterministic: no model involved. */
  RULE_MAPPED(0.95),
  /** Produced by the LLM and compiled on the first attempt. */
  AI_VERIFIED(0.70),
  /** Produced by the LLM, failed to compile, and compiled after one repair round. */
  AI_REPAIRED(0.50),
  /** Stubbed with UnsupportedOperationException for a human to finish. */
  MANUAL_REQUIRED(0.00);

  private final double confidence;

  Strategy(double confidence) {
    this.confidence = confidence;
  }

  public double confidence() {
    return confidence;
  }

  /** The weaker of two strategies, which is what a file containing both deserves. */
  public static Strategy weakest(Strategy a, Strategy b) {
    return a.confidence <= b.confidence ? a : b;
  }
}
