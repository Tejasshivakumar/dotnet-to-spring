package com.portway.core.ir;

import java.util.List;

/**
 * A reference to a type as written in C# source, before any mapping to Java.
 *
 * @param name the bare name as written: {@code int}, {@code List}, {@code BookDto}
 * @param typeArgs generic arguments, empty for non-generic types
 * @param nullable true for {@code int?} or, under nullable reference types, {@code string?}
 * @param arrayRank 0 for a non-array, 1 for {@code T[]}, 2 for {@code T[][]}
 */
public record TypeRef(String name, List<TypeRef> typeArgs, boolean nullable, int arrayRank) {

  public TypeRef {
    typeArgs = List.copyOf(typeArgs == null ? List.of() : typeArgs);
  }

  /** A plain, non-generic, non-nullable, non-array reference. */
  public static TypeRef of(String name) {
    return new TypeRef(name, List.of(), false, 0);
  }

  /** A generic reference such as {@code List<Book>}. */
  public static TypeRef generic(String name, TypeRef... args) {
    return new TypeRef(name, List.of(args), false, 0);
  }

  public boolean isGeneric() {
    return !typeArgs.isEmpty();
  }

  public boolean isArray() {
    return arrayRank > 0;
  }
}
