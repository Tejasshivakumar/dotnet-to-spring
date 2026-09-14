package com.portway.core.ir;

import java.util.List;

/**
 * A method declaration.
 *
 * <p>{@code bodyRaw} holds the exact C# source text of the body, braces included, whitespace
 * preserved. The IR models structure only; statements and expressions stay as text. That text is
 * needed in three places: token rewriting, the LLM prompt, and the "original C#" comment block on
 * methods that could not be migrated.
 *
 * @param bodyRaw exact source text, or null for an abstract or interface method
 * @param startLine 1-based first line in the C# file, used to map findings back to source
 */
public record MethodDecl(
    String name,
    TypeRef returnType,
    List<ParamDecl> params,
    List<String> modifiers,
    List<AttributeUse> attributes,
    String bodyRaw,
    int startLine,
    int endLine,
    String docComment) {

  public MethodDecl {
    params = List.copyOf(params == null ? List.of() : params);
    modifiers = List.copyOf(modifiers == null ? List.of() : modifiers);
    attributes = List.copyOf(attributes == null ? List.of() : attributes);
  }

  public boolean isAsync() {
    return modifiers.contains("async");
  }

  public boolean hasBody() {
    return bodyRaw != null && !bodyRaw.isBlank();
  }
}
