package com.portway.core.classify;

import com.portway.core.ir.ClassRole;
import com.portway.core.ir.PropertyDecl;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import com.portway.core.ir.TypeRef;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * A type is an entity when some DbContext in the project exposes a {@code DbSet<T>} of it.
 *
 * <p>That is the definition EF itself uses, and it beats guessing from attributes: an entity need
 * not carry a single annotation to be mapped.
 *
 * <p>Deliberately does not depend on DbContexts having been classified first — it looks for the
 * base type directly — so the chain has no hidden ordering requirement.
 */
public class EntityClassifier implements Classifier {

  @Override
  public Optional<ClassRole> classify(TypeDecl type, SourceProject project) {
    if (entityNames(project).contains(type.name())) {
      return Optional.of(ClassRole.ENTITY);
    }
    // Fallback: EF mapping attributes are strong evidence on their own. A class
    // carrying [Table] or [Key] is an entity even when its DbContext is not part
    // of the migrated slice, which is the normal case when migrating a subset of
    // a solution one project at a time.
    if (type.hasAttribute("Table") || hasKeyProperty(type)) {
      return Optional.of(ClassRole.ENTITY);
    }
    return Optional.empty();
  }

  private static boolean hasKeyProperty(TypeDecl type) {
    return type.properties().stream().anyMatch(p -> p.hasAttribute("Key"));
  }

  /** Every {@code T} appearing as {@code DbSet<T>} on any DbContext in the project. */
  public static Set<String> entityNames(SourceProject project) {
    Set<String> names = new LinkedHashSet<>();
    project
        .allTypes()
        .filter(t -> t.hasBaseType("DbContext"))
        .forEach(
            context -> {
              for (PropertyDecl property : context.properties()) {
                TypeRef propertyType = property.type();
                if (propertyType.name().equals("DbSet") && !propertyType.typeArgs().isEmpty()) {
                  names.add(propertyType.typeArgs().get(0).name());
                }
              }
            });
    return names;
  }
}
