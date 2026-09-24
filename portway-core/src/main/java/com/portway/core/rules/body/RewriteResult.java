package com.portway.core.rules.body;

import com.portway.core.report.FindingCode;
import java.util.List;
import java.util.Set;

/**
 * The outcome of rewriting one method body.
 *
 * @param javaBody for tier A, the Java statements without the enclosing braces, with type references
 *     written as import markers (see {@link JavaImports}); null otherwise
 * @param reason for tiers B and C, the first thing the rules could not handle, phrased for the
 *     reviewer
 * @param notes lossy-but-translated semantics to report even when the rewrite succeeded
 * @param repositories entities whose Spring Data repository the body now calls
 */
public record RewriteResult(
    Tier tier,
    String javaBody,
    String reason,
    List<FindingCode> notes,
    Set<String> repositories) {

  public RewriteResult {
    notes = List.copyOf(notes == null ? List.of() : notes);
    repositories = Set.copyOf(repositories == null ? Set.of() : repositories);
  }

  public static RewriteResult bail(Tier tier, String reason, List<FindingCode> notes) {
    return new RewriteResult(tier, null, reason, notes, Set.of());
  }

  public boolean succeeded() {
    return tier == Tier.A;
  }
}
