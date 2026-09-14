package com.portway.core.ir;

import java.util.List;

/**
 * A property declaration. C# auto-properties become a private field plus getter and setter on the
 * Java side, which is why the accessors are modelled explicitly.
 *
 * @param initializer source text of the initializer, or null when there is none
 * @param docComment the XML doc comment, or null
 */
public record PropertyDecl(
    String name,
    TypeRef type,
    List<String> modifiers,
    List<AttributeUse> attributes,
    boolean hasGetter,
    boolean hasSetter,
    String initializer,
    String docComment) {

  public PropertyDecl {
    modifiers = List.copyOf(modifiers == null ? List.of() : modifiers);
    attributes = List.copyOf(attributes == null ? List.of() : attributes);
  }

  public static PropertyDecl auto(String name, TypeRef type) {
    return new PropertyDecl(name, type, List.of("public"), List.of(), true, true, null, null);
  }
}
