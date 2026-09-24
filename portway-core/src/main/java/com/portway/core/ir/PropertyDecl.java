package com.portway.core.ir;

import java.util.List;

/**
 * A property declaration. C# auto-properties become a private field plus getter and setter on the
 * Java side, which is why the accessors are modelled explicitly.
 *
 * @param initializer source text of a real initializer ({@code = string.Empty}), or null. Never an
 *     expression body: {@code public bool InStock => StockCount > 0;} has no initial value.
 * @param docComment the XML doc comment, or null
 * @param getterBody exact source of a getter with logic, either a block ({@code { return x; }}) or
 *     an arrow form ({@code => StockCount > 0}); null for an auto-getter
 * @param setterBody exact source of a setter with logic, or null for an auto-setter
 */
public record PropertyDecl(
    String name,
    TypeRef type,
    List<String> modifiers,
    List<AttributeUse> attributes,
    boolean hasGetter,
    boolean hasSetter,
    String initializer,
    String docComment,
    String getterBody,
    String setterBody) {

  public PropertyDecl {
    modifiers = List.copyOf(modifiers == null ? List.of() : modifiers);
    attributes = List.copyOf(attributes == null ? List.of() : attributes);
  }

  /** An auto-property: accessors without bodies. */
  public PropertyDecl(
      String name,
      TypeRef type,
      List<String> modifiers,
      List<AttributeUse> attributes,
      boolean hasGetter,
      boolean hasSetter,
      String initializer,
      String docComment) {
    this(
        name, type, modifiers, attributes, hasGetter, hasSetter, initializer, docComment, null,
        null);
  }

  public boolean hasAttribute(String attributeName) {
    return attributes.stream().anyMatch(a -> a.name().equals(attributeName));
  }

  /**
   * A property whose getter has logic and which has no setter is computed, not stored: {@code
   * public bool InStock => StockCount > 0;} has no column behind it.
   */
  public boolean isComputed() {
    return !hasSetter && getterBody != null;
  }

  /**
   * True when an accessor carries logic that a plain field plus getter and setter would lose, such
   * as validation in a setter.
   */
  public boolean hasAccessorLogic() {
    return getterBody != null || setterBody != null;
  }

  /**
   * The getter as a single expression, for arrow-bodied getters only: {@code StockCount > 0} from
   * {@code => StockCount > 0}. Null for block getters and auto-getters.
   */
  public String getterExpression() {
    if (getterBody == null || !getterBody.startsWith("=>")) {
      return null;
    }
    return getterBody.substring(2).strip();
  }

  public static PropertyDecl auto(String name, TypeRef type) {
    return new PropertyDecl(name, type, List.of("public"), List.of(), true, true, null, null);
  }
}
