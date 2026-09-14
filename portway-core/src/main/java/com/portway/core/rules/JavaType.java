package com.portway.core.rules;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A Java type, resolved. This is the output side of {@link TypeMapper}: no C# concepts survive
 * here.
 *
 * @param packageName empty for primitives and for the default package
 * @param simpleName the type name without package or generic arguments
 * @param typeArgs resolved generic arguments, empty for non-generic types
 * @param primitive true for int, long, boolean and friends, which are never imported and never
 *     nullable
 * @param arrayDimensions 0 for a non-array
 * @param wildcard true when this should render as {@code Foo<?>} regardless of type arguments
 */
public record JavaType(
    String packageName,
    String simpleName,
    List<JavaType> typeArgs,
    boolean primitive,
    int arrayDimensions,
    boolean wildcard) {

  public JavaType {
    typeArgs = List.copyOf(typeArgs == null ? List.of() : typeArgs);
    packageName = packageName == null ? "" : packageName;
  }

  public static final JavaType VOID = primitive("void");
  public static final JavaType OBJECT = of("java.lang.Object");

  /** A primitive: no package, never imported. */
  public static JavaType primitive(String name) {
    return new JavaType("", name, List.of(), true, 0, false);
  }

  /** Parses a fully qualified name such as {@code java.math.BigDecimal}. */
  public static JavaType of(String qualifiedName) {
    int lastDot = qualifiedName.lastIndexOf('.');
    if (lastDot < 0) {
      return new JavaType("", qualifiedName, List.of(), false, 0, false);
    }
    return new JavaType(
        qualifiedName.substring(0, lastDot),
        qualifiedName.substring(lastDot + 1),
        List.of(),
        false,
        0,
        false);
  }

  public static JavaType of(String qualifiedName, List<JavaType> typeArgs) {
    return of(qualifiedName).withTypeArgs(typeArgs);
  }

  public JavaType withTypeArgs(List<JavaType> args) {
    return new JavaType(packageName, simpleName, args, primitive, arrayDimensions, wildcard);
  }

  public JavaType withArrayDimensions(int dimensions) {
    return new JavaType(packageName, simpleName, typeArgs, primitive, dimensions, wildcard);
  }

  public JavaType asWildcard() {
    return new JavaType(packageName, simpleName, typeArgs, primitive, arrayDimensions, true);
  }

  public String qualifiedName() {
    return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
  }

  /**
   * True when a Java file using this type needs an import for it. Primitives have none, and
   * java.lang is implicit.
   */
  public boolean needsImport() {
    return !primitive && !packageName.isEmpty() && !packageName.equals("java.lang");
  }

  /** How the type is written in source, assuming its import is present. */
  public String displayName() {
    StringBuilder sb = new StringBuilder(simpleName);
    if (wildcard) {
      sb.append("<?>");
    } else if (!typeArgs.isEmpty()) {
      sb.append(
          typeArgs.stream()
              .map(JavaType::displayName)
              .collect(Collectors.joining(", ", "<", ">")));
    }
    sb.append("[]".repeat(Math.max(0, arrayDimensions)));
    return sb.toString();
  }

  @Override
  public String toString() {
    return displayName();
  }
}
