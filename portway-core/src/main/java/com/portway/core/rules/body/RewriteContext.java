package com.portway.core.rules.body;

import java.util.Map;
import java.util.Set;

/**
 * Everything the body rewriter needs to know about the world outside the body.
 *
 * <p>Token rules alone cannot tell {@code book.Title} (a property, so {@code book.getTitle()}) from
 * {@code Genre.Fiction} (an enum constant, left alone), or know that {@code _db.Books} is now a
 * {@code BookRepository}. The generators build this from the IR for each class they emit.
 *
 * @param fieldRenames C# field name to Java field name, e.g. {@code _bookService -> bookService}
 * @param dbSets per DbContext-typed field, DbSet property name to entity name, e.g. {@code _db ->
 *     {Books -> Book}}
 * @param properties property name to its Java accessors, across every generated POJO
 * @param typeNames names of project types and enums, which are never property receivers
 * @param ownMethods C# name to Java name for methods of the class being generated, so unqualified
 *     calls such as {@code ToResponse(b)} can be renamed
 * @param decimalMembers properties typed {@code decimal}, whose arithmetic cannot be translated by
 *     token rules because BigDecimal has no operators
 * @param actionRoutes controller action name to its full route template, for {@code
 *     CreatedAtAction(nameof(GetById), ...)}
 * @param returnsVoid whether the Java method returns void, which decides what an expression body
 *     becomes
 * @param wrapBareReturns true in controller actions returning {@code ActionResult<T>}, where C#
 *     converts {@code return value;} into a 200 implicitly and Java needs {@code
 *     ResponseEntity.ok(value)}
 * @param loggerFields C# fields holding an {@code ILogger}, whose {@code LogInformation} calls
 *     become SLF4J calls on the generated {@code log} field
 * @param entityKeys entity name to its key property, so a lookup by key becomes {@code findById}
 *     rather than a scan of the whole table
 */
public record RewriteContext(
    Map<String, String> fieldRenames,
    Map<String, Map<String, String>> dbSets,
    Map<String, Accessor> properties,
    Set<String> typeNames,
    Map<String, String> ownMethods,
    Set<String> decimalMembers,
    Map<String, String> actionRoutes,
    boolean returnsVoid,
    boolean wrapBareReturns,
    Set<String> loggerFields,
    Map<String, String> entityKeys) {

  /** A property's Java accessor names. */
  public record Accessor(String getter, String setter) {}

  public RewriteContext {
    fieldRenames = Map.copyOf(fieldRenames == null ? Map.of() : fieldRenames);
    dbSets = Map.copyOf(dbSets == null ? Map.of() : dbSets);
    properties = Map.copyOf(properties == null ? Map.of() : properties);
    typeNames = Set.copyOf(typeNames == null ? Set.of() : typeNames);
    ownMethods = Map.copyOf(ownMethods == null ? Map.of() : ownMethods);
    decimalMembers = Set.copyOf(decimalMembers == null ? Set.of() : decimalMembers);
    actionRoutes = Map.copyOf(actionRoutes == null ? Map.of() : actionRoutes);
    loggerFields = Set.copyOf(loggerFields == null ? Set.of() : loggerFields);
    entityKeys = Map.copyOf(entityKeys == null ? Map.of() : entityKeys);
  }

  public RewriteContext(
      Map<String, String> fieldRenames,
      Map<String, Map<String, String>> dbSets,
      Map<String, Accessor> properties,
      Set<String> typeNames,
      Map<String, String> ownMethods,
      Set<String> decimalMembers,
      Map<String, String> actionRoutes,
      boolean returnsVoid,
      boolean wrapBareReturns) {
    this(
        fieldRenames, dbSets, properties, typeNames, ownMethods, decimalMembers, actionRoutes,
        returnsVoid, wrapBareReturns, Set.of(), Map.of());
  }

  /** A context knowing nothing about the surrounding project. Useful for tests. */
  public static RewriteContext empty() {
    return new RewriteContext(null, null, null, null, null, null, null, false, false, null, null);
  }

  public RewriteContext withReturnsVoid(boolean value) {
    return new RewriteContext(
        fieldRenames, dbSets, properties, typeNames, ownMethods, decimalMembers, actionRoutes,
        value, wrapBareReturns, loggerFields, entityKeys);
  }

  public RewriteContext withWrapBareReturns(boolean value) {
    return new RewriteContext(
        fieldRenames, dbSets, properties, typeNames, ownMethods, decimalMembers, actionRoutes,
        returnsVoid, value, loggerFields, entityKeys);
  }

  /** The repository field that replaces a DbSet of {@code entity}: {@code Book -> bookRepository}. */
  public static String repositoryField(String entity) {
    return Character.toLowerCase(entity.charAt(0)) + entity.substring(1) + "Repository";
  }
}
