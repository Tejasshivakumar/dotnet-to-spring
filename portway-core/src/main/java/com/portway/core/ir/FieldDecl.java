package com.portway.core.ir;

import java.util.List;

/**
 * A field declaration.
 *
 * @param initializer source text of the initializer, or null when there is none
 */
public record FieldDecl(
    String name,
    TypeRef type,
    List<String> modifiers,
    List<AttributeUse> attributes,
    String initializer) {

  public FieldDecl {
    modifiers = List.copyOf(modifiers == null ? List.of() : modifiers);
    attributes = List.copyOf(attributes == null ? List.of() : attributes);
  }

  public boolean isReadonly() {
    return modifiers.contains("readonly");
  }
}
