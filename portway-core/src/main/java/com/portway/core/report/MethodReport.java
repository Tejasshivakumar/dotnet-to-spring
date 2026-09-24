package com.portway.core.report;

import com.portway.core.rules.body.Tier;
import java.util.List;

/**
 * What happened to one C# method.
 *
 * <p>Carries everything the LLM layer needs to translate the method without going back to the IR:
 * the Java signature it must fit, the fields it can use, and the original body. That keeps the AI
 * layer on the far side of a narrow interface.
 *
 * @param key stable identity across regenerations, {@code Type.Method(ParamType,...)} in C# terms
 * @param tier the route the rule engine chose before any override
 * @param strategy how the body that was actually emitted came to be
 * @param reason why the rules gave up, or why the method was demoted; null for rule-mapped
 * @param javaClassName the generated class, after renames
 * @param javaSignature the generated method signature, for the model to fill
 * @param javaFields the generated class's fields, as declarations
 * @param csharpSource the original method as written, signature and body
 */
public record MethodReport(
    String key,
    String typeName,
    String methodName,
    String sourcePath,
    int startLine,
    int endLine,
    String generatedPath,
    Tier tier,
    Strategy strategy,
    String reason,
    String javaClassName,
    String javaSignature,
    List<String> javaFields,
    String csharpSource) {

  public MethodReport {
    javaFields = List.copyOf(javaFields == null ? List.of() : javaFields);
  }

  /** Whether the LLM may attempt this method: the rules gave up, but not for a hard reason. */
  public boolean aiEligible() {
    return tier == Tier.B;
  }
}
