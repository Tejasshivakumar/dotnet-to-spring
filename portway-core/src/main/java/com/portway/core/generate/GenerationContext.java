package com.portway.core.generate;

import com.portway.core.ir.ClassRole;
import com.portway.core.ir.PropertyDecl;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import com.portway.core.ir.TypeKind;
import com.portway.core.ir.TypeRef;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Whole-project facts the generators need while emitting a single file.
 *
 * <p>A property's Java shape depends on what its type <em>is</em> elsewhere in the project: a
 * reference to an entity is an association, a reference to an enum needs {@code @Enumerated}, and a
 * reference to anything else is a plain column. None of that is visible from the property alone.
 */
public record GenerationContext(
    Set<String> entityNames,
    Set<String> enumNames,
    Map<String, TypeDecl> typesByName,
    String basePackage) {

  public static GenerationContext from(SourceProject project, String basePackage) {
    Set<String> entities = new LinkedHashSet<>();
    Set<String> enums = new LinkedHashSet<>();
    Map<String, TypeDecl> byName = new LinkedHashMap<>();

    project
        .allTypes()
        .forEach(
            type -> {
              byName.put(type.name(), type);
              if (type.role() == ClassRole.ENTITY) {
                entities.add(type.name());
              }
              if (type.kind() == TypeKind.ENUM) {
                enums.add(type.name());
              }
            });

    return new GenerationContext(entities, enums, byName, basePackage);
  }

  public String entityPackage() {
    return basePackage + ".domain";
  }

  public String repositoryPackage() {
    return basePackage + ".repository";
  }

  public boolean isEntity(String typeName) {
    return entityNames.contains(typeName);
  }

  public boolean isEnum(String typeName) {
    return enumNames.contains(typeName);
  }

  /** True when the property holds a single entity: a many-to-one association. */
  public boolean isSingleAssociation(PropertyDecl property) {
    TypeRef type = property.type();
    return type.typeArgs().isEmpty() && !type.isArray() && isEntity(type.name());
  }

  /** True when the property holds a collection of entities: a one-to-many association. */
  public boolean isCollectionAssociation(PropertyDecl property) {
    TypeRef type = property.type();
    return type.typeArgs().size() == 1 && isEntity(type.typeArgs().get(0).name());
  }

  /** The element type of a collection association. */
  public String collectionElement(PropertyDecl property) {
    return property.type().typeArgs().get(0).name();
  }

  /**
   * The field on the other side that owns the association, for {@code @OneToMany(mappedBy)}.
   *
   * <p>Looks for a property on the target entity whose type is the owning entity. Returns empty
   * when there is no such property, in which case the association is emitted without mappedBy and
   * JPA defaults to a join table — which is why that case also earns a finding.
   */
  public Optional<String> inverseFieldName(String targetEntity, String owningEntity) {
    TypeDecl target = typesByName.get(targetEntity);
    if (target == null) {
      return Optional.empty();
    }
    return target.properties().stream()
        .filter(p -> p.type().typeArgs().isEmpty() && p.type().name().equals(owningEntity))
        .map(p -> Names.fieldName(p.name()))
        .findFirst();
  }

  /**
   * The scalar foreign-key property that shadows an association, if the entity declares one.
   *
   * <p>EF models let you expose both {@code Author Author} and {@code long AuthorId}. In JPA the
   * association owns the column, so the scalar has to be marked read-only or Hibernate rejects the
   * duplicate mapping.
   */
  public boolean isShadowForeignKey(TypeDecl entity, PropertyDecl property) {
    if (!property.type().typeArgs().isEmpty() || isEntity(property.type().name())) {
      return false;
    }
    String name = property.name();
    if (!name.endsWith("Id") || name.length() <= 2) {
      return false;
    }
    String associationName = name.substring(0, name.length() - 2);
    return entity.properties().stream()
        .anyMatch(p -> p.name().equals(associationName) && isEntity(p.type().name()));
  }
}
