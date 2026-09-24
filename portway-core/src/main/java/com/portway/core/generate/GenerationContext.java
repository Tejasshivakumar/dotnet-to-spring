package com.portway.core.generate;

import com.portway.core.ir.ClassRole;
import com.portway.core.ir.PropertyDecl;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import com.portway.core.ir.TypeKind;
import com.portway.core.ir.TypeRef;
import com.portway.core.rules.JavaType;
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
    String basePackage,
    Map<String, String> typeRenames) {

  public GenerationContext {
    typeRenames = Map.copyOf(typeRenames == null ? Map.of() : typeRenames);
  }

  public GenerationContext(
      Set<String> entityNames,
      Set<String> enumNames,
      Map<String, TypeDecl> typesByName,
      String basePackage) {
    this(entityNames, enumNames, typesByName, basePackage, Map.of());
  }

  public static GenerationContext from(SourceProject project, String basePackage) {
    return from(project, basePackage, MigrationOptions.defaults());
  }

  /**
   * Builds the context, including the renames Java convention asks for: {@code IBookService}
   * becomes {@code BookService}, and the class that implemented it becomes {@code BookServiceImpl}.
   */
  public static GenerationContext from(
      SourceProject project, String basePackage, MigrationOptions options) {
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

    Map<String, String> renames = new LinkedHashMap<>();
    if (!options.keepInterfacePrefix()) {
      project
          .typesWithRole(ClassRole.SERVICE_INTERFACE)
          .forEach(i -> renames.put(i.name(), Names.stripInterfacePrefix(i.name())));
      project
          .typesWithRole(ClassRole.SERVICE)
          .forEach(
              impl -> {
                boolean collides =
                    impl.baseTypes().stream()
                        .anyMatch(b -> impl.name().equals(renames.get(b.name())));
                if (collides) {
                  renames.put(impl.name(), impl.name() + "Impl");
                }
              });
    }
    return new GenerationContext(entities, enums, byName, basePackage, renames);
  }

  public String dtoPackage() {
    return basePackage + ".dto";
  }

  public String servicePackage() {
    return basePackage + ".service";
  }

  public String controllerPackage() {
    return basePackage + ".controller";
  }

  public String configPackage() {
    return basePackage + ".config";
  }

  /** Types the classifier could not place. Generated as plain classes and flagged. */
  public String modelPackage() {
    return basePackage + ".model";
  }

  /** The Java name of a project type, after any convention rename. */
  public String javaName(String csharpName) {
    return typeRenames.getOrDefault(csharpName, csharpName);
  }

  /** The package a project type is generated into, or null for a name that is not one. */
  public String packageOf(String csharpName) {
    TypeDecl type = typesByName.get(csharpName);
    if (type == null) {
      return null;
    }
    if (type.kind() == TypeKind.ENUM) {
      return entityPackage();
    }
    return switch (type.role()) {
      case ENTITY -> entityPackage();
      case DTO -> dtoPackage();
      case SERVICE, SERVICE_INTERFACE -> servicePackage();
      case CONTROLLER -> controllerPackage();
      case CONFIGURATION -> configPackage();
      case REPOSITORY -> repositoryPackage();
      default -> modelPackage();
    };
  }

  /**
   * Places project types referenced from a mapped Java type into the package and name they were
   * generated as. The type mapper passes unknown names through without a package, because it has
   * no idea where the generator will put them.
   */
  public JavaType qualify(JavaType type) {
    java.util.List<JavaType> args = type.typeArgs().stream().map(this::qualify).toList();
    JavaType withArgs = type.typeArgs().isEmpty() ? type : type.withTypeArgs(args);
    if (!type.packageName().isEmpty() || type.primitive()) {
      return withArgs;
    }
    String pkg = packageOf(type.simpleName());
    if (pkg == null) {
      return withArgs;
    }
    return new JavaType(
        pkg,
        javaName(type.simpleName()),
        withArgs.typeArgs(),
        false,
        type.arrayDimensions(),
        type.wildcard());
  }

  /** The path of a generated Java source file. */
  public static String javaPath(String packageName, String simpleName) {
    return "src/main/java/" + packageName.replace('.', '/') + "/" + simpleName + ".java";
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
