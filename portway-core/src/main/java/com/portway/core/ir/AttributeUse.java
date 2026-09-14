package com.portway.core.ir;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One attribute applied to a declaration, e.g. {@code [Route("api/[controller]")]} or {@code
 * [StringLength(200, MinimumLength = 3)]}.
 *
 * @param name the attribute name with any {@code Attribute} suffix already stripped
 * @param positionalArgs argument source text in order, string literals still quoted
 * @param namedArgs named arguments such as {@code MinimumLength = 3}, keyed by name
 */
public record AttributeUse(
    String name, List<String> positionalArgs, Map<String, String> namedArgs) {

  public AttributeUse {
    positionalArgs = List.copyOf(positionalArgs == null ? List.of() : positionalArgs);
    namedArgs = Map.copyOf(namedArgs == null ? Map.of() : namedArgs);
  }

  public static AttributeUse of(String name, String... positionalArgs) {
    return new AttributeUse(name, List.of(positionalArgs), Map.of());
  }

  /** The first positional argument with surrounding double quotes removed, if there is one. */
  public Optional<String> firstArgUnquoted() {
    if (positionalArgs.isEmpty()) {
      return Optional.empty();
    }
    String raw = positionalArgs.get(0).trim();
    if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
      return Optional.of(raw.substring(1, raw.length() - 1));
    }
    return Optional.of(raw);
  }
}
