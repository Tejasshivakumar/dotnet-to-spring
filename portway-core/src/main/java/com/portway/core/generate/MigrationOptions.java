package com.portway.core.generate;

/**
 * Knobs for a migration run.
 *
 * @param basePackage root Java package for generated code; when null it is derived from the C#
 *     root namespace, lowercased and sanitised
 * @param keepInterfacePrefix keep C#'s {@code IBookService} naming instead of renaming to
 *     {@code BookService} / {@code BookServiceImpl}. Java convention is to rename, so this
 *     defaults to false, but the C# names are sometimes worth keeping during a phased migration.
 */
public record MigrationOptions(String basePackage, boolean keepInterfacePrefix) {

  public static MigrationOptions defaults() {
    return new MigrationOptions(null, false);
  }

  public MigrationOptions withBasePackage(String basePackage) {
    return new MigrationOptions(basePackage, keepInterfacePrefix);
  }
}
