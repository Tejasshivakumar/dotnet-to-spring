package com.portway.core.generate;

import com.portway.core.ir.ClassRole;
import com.portway.core.ir.PropertyDecl;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import com.portway.core.ir.TypeKind;
import com.portway.core.rules.JavaType;
import com.portway.core.rules.TypeMapper;
import com.portway.core.rules.body.RewriteContext.Accessor;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Project-wide facts every body rewrite needs, computed once per migration.
 *
 * <p>The rewriter turns {@code book.Title} into {@code book.getTitle()} only for names it knows are
 * properties of a generated POJO, and it must use the same getter name the POJO generator emitted:
 * {@code isInStock()} for a primitive boolean, {@code getInStock()} for a boxed one.
 *
 * @param properties property name to accessor names, across every type generated with accessors
 * @param typeNames every project type name, which is never a property receiver
 * @param decimalMembers properties typed decimal anywhere in the project
 * @param entityKeys entity name to key property name
 */
public record ProjectFacts(
    Map<String, Accessor> properties,
    Set<String> typeNames,
    Set<String> decimalMembers,
    Map<String, String> entityKeys) {

  public static ProjectFacts of(SourceProject project, TypeMapper typeMapper) {
    Map<String, Accessor> properties = new LinkedHashMap<>();
    Map<String, Set<String>> getterVariants = new LinkedHashMap<>();
    Set<String> typeNames = new LinkedHashSet<>();
    Set<String> decimals = new LinkedHashSet<>();
    Map<String, String> keys = new LinkedHashMap<>();

    project
        .allTypes()
        .forEach(
            type -> {
              typeNames.add(type.name());
              if (!hasAccessors(type)) {
                return;
              }
              for (PropertyDecl property : type.properties()) {
                if (property.type().name().equals("decimal")) {
                  decimals.add(property.name());
                }
                if (isKey(type, property)) {
                  keys.putIfAbsent(type.name(), property.name());
                }
                JavaType mapped = typeMapper.map(property.type(), isKey(type, property)).type();
                boolean primitiveBoolean = mapped.primitive() && mapped.simpleName().equals("boolean");
                String field = Names.fieldName(property.name());
                getterVariants
                    .computeIfAbsent(property.name(), k -> new LinkedHashSet<>())
                    .add(Names.getterName(field, primitiveBoolean));
              }
            });

    getterVariants.forEach(
        (name, getters) -> {
          // When one project type has `bool Active` and another `bool? Active`, the two getters
          // differ. The get- form is the safer guess; the compile check catches a wrong one.
          String getter =
              getters.size() == 1
                  ? getters.iterator().next()
                  : Names.getterName(Names.fieldName(name), false);
          properties.put(name, new Accessor(getter, Names.setterName(Names.fieldName(name))));
        });
    return new ProjectFacts(properties, typeNames, decimals, keys);
  }

  /** Types generated as classes with private fields and accessors. */
  static boolean hasAccessors(TypeDecl type) {
    if (type.kind() == TypeKind.ENUM || type.kind() == TypeKind.INTERFACE) {
      return false;
    }
    return switch (type.role()) {
      case ENTITY, DTO, CONFIGURATION, UNKNOWN -> true;
      default -> false;
    };
  }

  /** Mirrors the entity generator's identifier rule, which boxes the key. */
  static boolean isKey(TypeDecl type, PropertyDecl property) {
    if (type.role() != ClassRole.ENTITY) {
      return false;
    }
    if (property.hasAttribute("Key")) {
      return true;
    }
    boolean anyExplicitKey = type.properties().stream().anyMatch(p -> p.hasAttribute("Key"));
    return !anyExplicitKey
        && (property.name().equals("Id") || property.name().equals(type.name() + "Id"));
  }
}
