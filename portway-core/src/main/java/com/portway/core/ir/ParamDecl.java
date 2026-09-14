package com.portway.core.ir;

import java.util.List;

/**
 * A method or constructor parameter.
 *
 * @param defaultValue source text of the default, or null when the parameter is required
 */
public record ParamDecl(
    String name, TypeRef type, List<AttributeUse> attributes, String defaultValue) {

  public ParamDecl {
    attributes = List.copyOf(attributes == null ? List.of() : attributes);
  }

  public static ParamDecl of(String name, TypeRef type) {
    return new ParamDecl(name, type, List.of(), null);
  }
}
