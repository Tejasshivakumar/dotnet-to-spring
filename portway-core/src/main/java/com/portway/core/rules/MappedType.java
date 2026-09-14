package com.portway.core.rules;

import com.portway.core.report.FindingCode;
import java.util.List;

/**
 * The result of mapping one C# type: the Java type, plus anything lossy about getting there.
 *
 * <p>The mapper reports codes rather than full findings because it has no idea which file or line
 * it is working on. Callers, which do, turn these into {@link com.portway.core.report.Finding}s.
 */
public record MappedType(JavaType type, List<FindingCode> notes) {

  public MappedType {
    notes = List.copyOf(notes == null ? List.of() : notes);
  }

  public static MappedType clean(JavaType type) {
    return new MappedType(type, List.of());
  }

  public boolean isLossy() {
    return !notes.isEmpty();
  }
}
