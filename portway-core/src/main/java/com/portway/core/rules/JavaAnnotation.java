package com.portway.core.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Java annotation to emit, kept as plain data rather than a JavaPoet {@code AnnotationSpec} so
 * the mapping rules stay testable without touching a code generator.
 *
 * @param type the annotation type
 * @param members member name to literal source text, in declaration order; empty for a marker
 *     annotation such as {@code @Id}
 */
public record JavaAnnotation(JavaType type, Map<String, String> members) {

  public JavaAnnotation {
    // Ordered: these become annotation arguments and golden tests compare text.
    members =
        Collections.unmodifiableMap(new LinkedHashMap<>(members == null ? Map.of() : members));
  }

  public static JavaAnnotation marker(String qualifiedName) {
    return new JavaAnnotation(JavaType.of(qualifiedName), Map.of());
  }

  public static JavaAnnotation of(String qualifiedName, String member, String value) {
    return new JavaAnnotation(JavaType.of(qualifiedName), Map.of(member, value));
  }

  /** A single unnamed argument, rendered as {@code @Foo("bar")} rather than {@code value = }. */
  public static JavaAnnotation value(String qualifiedName, String value) {
    return new JavaAnnotation(JavaType.of(qualifiedName), Map.of("value", value));
  }

  public String simpleName() {
    return type.simpleName();
  }

  /** Combines two annotations of the same type, with {@code other}'s members winning on clash. */
  public JavaAnnotation mergedWith(JavaAnnotation other) {
    Map<String, String> combined = new LinkedHashMap<>(members);
    combined.putAll(other.members());
    return new JavaAnnotation(type, combined);
  }
}
