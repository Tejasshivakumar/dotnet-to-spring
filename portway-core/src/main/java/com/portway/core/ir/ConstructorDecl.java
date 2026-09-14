package com.portway.core.ir;

import java.util.List;

/** A constructor declaration. Used mainly to recover dependency-injected fields. */
public record ConstructorDecl(List<ParamDecl> params, String bodyRaw, List<String> modifiers) {

  public ConstructorDecl {
    params = List.copyOf(params == null ? List.of() : params);
    modifiers = List.copyOf(modifiers == null ? List.of() : modifiers);
  }
}
