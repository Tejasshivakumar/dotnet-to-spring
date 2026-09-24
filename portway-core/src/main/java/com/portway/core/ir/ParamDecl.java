package com.portway.core.ir;

import java.util.List;

/**
 * A method or constructor parameter.
 *
 * @param defaultValue source text of the default, or null when the parameter is required
 * @param modifier {@code params}, {@code ref}, {@code out}, {@code in} or {@code this}, or null for
 *     an ordinary by-value parameter. Kept rather than dropped: {@code out} and {@code ref} change
 *     what a method means, and a signature that silently loses them is a different method.
 */
public record ParamDecl(
    String name, TypeRef type, List<AttributeUse> attributes, String defaultValue, String modifier) {

  public ParamDecl {
    attributes = List.copyOf(attributes == null ? List.of() : attributes);
  }

  /** An ordinary by-value parameter. */
  public ParamDecl(String name, TypeRef type, List<AttributeUse> attributes, String defaultValue) {
    this(name, type, attributes, defaultValue, null);
  }

  public static ParamDecl of(String name, TypeRef type) {
    return new ParamDecl(name, type, List.of(), null, null);
  }

  /** {@code params T[] values}: becomes a Java varargs parameter. */
  public boolean isParamsArray() {
    return "params".equals(modifier);
  }

  /**
   * {@code ref}, {@code out} or {@code in}: pass-by-reference, which Java does not have. A method
   * taking one cannot be translated by rule.
   */
  public boolean isByReference() {
    return "ref".equals(modifier) || "out".equals(modifier) || "in".equals(modifier);
  }

  public boolean hasAttribute(String attributeName) {
    return attributes.stream().anyMatch(a -> a.name().equals(attributeName));
  }
}
