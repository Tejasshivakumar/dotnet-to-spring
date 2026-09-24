package com.portway.core.rules.body;

/**
 * Which route a method body takes through the migration.
 *
 * <p>Tier A is the deterministic rule engine and is always tried first. Tier B is the LLM, reached
 * only when the rules could not account for every C#-specific token. Tier C never reaches the
 * model: the construct has no Java equivalent a translation could honestly produce, so the method
 * becomes a compiling stub and a line in the review queue.
 */
public enum Tier {
  /** Rewritten entirely by rules. */
  A,
  /** Rules could not finish; eligible for LLM translation. */
  B,
  /** No faithful translation exists; stubbed for a human. */
  C
}
