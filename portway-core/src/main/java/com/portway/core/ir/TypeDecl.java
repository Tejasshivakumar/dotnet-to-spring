package com.portway.core.ir;

import java.util.List;

/**
 * A single type declaration: class, interface, enum, record or struct.
 *
 * <p>{@code role} is left UNKNOWN by the parser and filled in later by the classifier, which needs
 * whole-project information to decide. See {@link ClassRole}.
 */
public record TypeDecl(
    TypeKind kind,
    String name,
    List<String> modifiers,
    List<TypeRef> baseTypes,
    List<AttributeUse> attributes,
    List<FieldDecl> fields,
    List<PropertyDecl> properties,
    List<MethodDecl> methods,
    List<ConstructorDecl> constructors,
    String docComment,
    ClassRole role) {

  public TypeDecl {
    modifiers = List.copyOf(modifiers == null ? List.of() : modifiers);
    baseTypes = List.copyOf(baseTypes == null ? List.of() : baseTypes);
    attributes = List.copyOf(attributes == null ? List.of() : attributes);
    fields = List.copyOf(fields == null ? List.of() : fields);
    properties = List.copyOf(properties == null ? List.of() : properties);
    methods = List.copyOf(methods == null ? List.of() : methods);
    constructors = List.copyOf(constructors == null ? List.of() : constructors);
    role = role == null ? ClassRole.UNKNOWN : role;
  }

  /** Returns a copy carrying the role the classifier decided on. */
  public TypeDecl withRole(ClassRole newRole) {
    return new TypeDecl(
        kind,
        name,
        modifiers,
        baseTypes,
        attributes,
        fields,
        properties,
        methods,
        constructors,
        docComment,
        newRole);
  }

  public boolean hasAttribute(String attributeName) {
    return attributes.stream().anyMatch(a -> a.name().equals(attributeName));
  }

  public boolean hasBaseType(String typeName) {
    return baseTypes.stream().anyMatch(t -> t.name().equals(typeName));
  }
}
