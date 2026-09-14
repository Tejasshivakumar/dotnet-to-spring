package com.portway.core.generate;

import java.util.Locale;

/** Naming conventions for crossing from C# to Java. */
public final class Names {

  private Names() {}

  /** {@code Title} becomes {@code title}; {@code ISBN} becomes {@code isbn}. */
  public static String fieldName(String propertyName) {
    if (propertyName == null || propertyName.isEmpty()) {
      return propertyName;
    }
    // A run of capitals is an acronym: ISBN -> isbn, but IDNumber -> idNumber.
    int upperRun = 0;
    while (upperRun < propertyName.length() && Character.isUpperCase(propertyName.charAt(upperRun))) {
      upperRun++;
    }
    if (upperRun <= 1) {
      return Character.toLowerCase(propertyName.charAt(0)) + propertyName.substring(1);
    }
    if (upperRun == propertyName.length()) {
      return propertyName.toLowerCase(Locale.ROOT);
    }
    return propertyName.substring(0, upperRun - 1).toLowerCase(Locale.ROOT)
        + propertyName.substring(upperRun - 1);
  }

  /** {@code title} becomes {@code getTitle}. */
  public static String getterName(String fieldName, boolean primitiveBoolean) {
    String suffix = capitalise(fieldName);
    return (primitiveBoolean ? "is" : "get") + suffix;
  }

  public static String setterName(String fieldName) {
    return "set" + capitalise(fieldName);
  }

  /** C# method names are PascalCase; Java's are camelCase. */
  public static String methodName(String csharpName) {
    String name = csharpName;
    // GetAllAsync -> getAll: the Async suffix is a C# convention that means nothing
    // once the method is synchronous.
    if (name.endsWith("Async") && name.length() > "Async".length()) {
      name = name.substring(0, name.length() - "Async".length());
    }
    return fieldName(name);
  }

  /** {@code IBookService} becomes {@code BookService}. */
  public static String stripInterfacePrefix(String interfaceName) {
    if (interfaceName.length() > 2
        && interfaceName.charAt(0) == 'I'
        && Character.isUpperCase(interfaceName.charAt(1))) {
      return interfaceName.substring(1);
    }
    return interfaceName;
  }

  /**
   * Derives a Java base package from a C# namespace.
   *
   * <p>Takes the root segment only: {@code Bookstore.Models} and {@code Bookstore.Controllers} must
   * land in one package tree, organised by role, not mirror the C# folder layout.
   */
  public static String basePackage(String csharpNamespace) {
    if (csharpNamespace == null || csharpNamespace.isBlank()) {
      return "migrated";
    }
    // A namespace of only separators splits to an empty array, so index 0 is not safe.
    String[] segments = csharpNamespace.split("\\.");
    if (segments.length == 0) {
      return "migrated";
    }
    String sanitised = sanitiseSegment(segments[0]);
    return sanitised.isEmpty() ? "migrated" : sanitised;
  }

  /** Lowercases and strips anything that cannot appear in a Java package segment. */
  public static String sanitiseSegment(String segment) {
    StringBuilder sb = new StringBuilder();
    for (char c : segment.toLowerCase(Locale.ROOT).toCharArray()) {
      if (Character.isLetterOrDigit(c)) {
        sb.append(c);
      }
    }
    // A segment cannot start with a digit.
    if (!sb.isEmpty() && Character.isDigit(sb.charAt(0))) {
      sb.insert(0, '_');
    }
    return sb.toString();
  }

  private static String capitalise(String name) {
    return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }
}
