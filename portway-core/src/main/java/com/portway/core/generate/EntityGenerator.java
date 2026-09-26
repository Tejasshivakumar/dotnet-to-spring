package com.portway.core.generate;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.portway.core.ir.PropertyDecl;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.TypeDecl;
import com.portway.core.report.Finding;
import com.portway.core.report.FindingCode;
import com.portway.core.rules.AttributeMapper;
import com.portway.core.rules.JavaAnnotation;
import com.portway.core.rules.JavaType;
import com.portway.core.rules.MappedType;
import com.portway.core.rules.TypeMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.Modifier;

/**
 * Turns an EF entity class into a JPA {@code @Entity}.
 *
 * <p>C# auto-properties become a private field plus a getter and setter. No Lombok: generated code
 * is read by a human before it is trusted, and a reviewer should not have to know what
 * {@code @Data} expands to in order to check the migration.
 */
public class EntityGenerator {

  private static final String JPA = "jakarta.persistence";

  private final TypeMapper typeMapper;
  private final AttributeMapper attributeMapper;
  private final JavaFormatter formatter;

  public EntityGenerator(
      TypeMapper typeMapper, AttributeMapper attributeMapper, JavaFormatter formatter) {
    this.typeMapper = typeMapper;
    this.attributeMapper = attributeMapper;
    this.formatter = formatter;
  }

  public GeneratedFile generate(TypeDecl entity, SourceFile source, GenerationContext context) {
    List<Finding> findings = new ArrayList<>();
    String packageName = context.entityPackage();

    TypeSpec.Builder builder = TypeSpec.classBuilder(entity.name()).addModifiers(Modifier.PUBLIC);

    String doc = Javadoc.fromXmlDoc(entity.docComment());
    if (doc != null) {
      builder.addJavadoc("$L\n", doc);
    }

    builder.addAnnotation(ClassName.get(JPA, "Entity"));
    AttributeMapper.Mapped classAttributes = attributeMapper.mapAll(entity.attributes());
    for (JavaAnnotation annotation : classAttributes.annotations()) {
      builder.addAnnotation(JavaPoetTypes.toAnnotationSpec(annotation));
    }
    note(findings, classAttributes.notes(), entity, source, null);

    List<MethodSpec> accessors = new ArrayList<>();

    for (PropertyDecl property : entity.properties()) {
      if (property.isComputed()) {
        if (property.getterExpression() == null) {
          findings.add(
              Finding.at(
                  FindingCode.METHOD_STUBBED,
                  entity.name() + "." + property.name() + " has a block getter that was not migrated.",
                  source.path(),
                  0));
          continue;
        }
        accessors.add(computedGetter(property, context));
        continue;
      }

      boolean isKey = isIdentifier(entity, property);
      // JpaRepository<Book, long> does not compile, and Spring Data reads a null
      // id as "not yet saved", which a primitive cannot express.
      MappedType mapped = typeMapper.map(property.type(), isKey);
      note(findings, mapped.notes(), entity, source, property.name());

      String fieldName = Names.fieldName(property.name());
      JavaType javaType = context.qualify(mapped.type());
      TypeName fieldType = JavaPoetTypes.toTypeName(javaType, packageName);

      FieldSpec.Builder field = FieldSpec.builder(fieldType, fieldName, Modifier.PRIVATE);
      // C# models lean on initializers for non-null defaults: `= string.Empty` on a
      // required column, `= new List<Book>()` on an association.
      com.palantir.javapoet.CodeBlock init =
          Initializers.translate(property.initializer(), javaType, context);
      if (init != null) {
        field.initializer(init);
      }
      if (property.hasAccessorLogic()) {
        findings.add(
            Finding.at(
                FindingCode.UNSUPPORTED_CONSTRUCT,
                entity.name() + "." + property.name() + " has accessor logic that was not"
                    + " migrated; it is now a plain field.",
                source.path(),
                0));
      }

      String propertyDoc = Javadoc.fromXmlDoc(property.docComment());
      if (propertyDoc != null) {
        field.addJavadoc("$L\n", propertyDoc);
      }

      for (AnnotationSpec annotation :
          annotationsFor(entity, property, context, findings, source)) {
        field.addAnnotation(annotation);
      }

      builder.addField(field.build());
      accessors.add(getter(fieldName, fieldType, mapped.type()));
      accessors.add(setter(fieldName, fieldType));
    }

    accessors.forEach(builder::addMethod);

    JavaFile javaFile =
        JavaFile.builder(packageName, builder.build())
            .skipJavaLangImports(true)
            .indent("  ")
            .build();

    String path = "src/main/java/" + packageName.replace('.', '/') + "/" + entity.name() + ".java";
    return new GeneratedFile(path, formatter.format(javaFile.toString()), source.path(), findings);
  }

  /**
   * Every annotation on one field: the attribute-derived ones, plus the association and enum
   * mappings that can only be decided with whole-project knowledge.
   */
  private List<AnnotationSpec> annotationsFor(
      TypeDecl entity,
      PropertyDecl property,
      GenerationContext context,
      List<Finding> findings,
      SourceFile source) {

    AttributeMapper.Mapped mapped = attributeMapper.mapAll(property.attributes());
    note(findings, mapped.notes(), entity, source, property.name());

    List<JavaAnnotation> annotations = new ArrayList<>(mapped.annotations());

    if (isIdentifier(entity, property)) {
      conventionKey(property, annotations);
    }

    if (context.isSingleAssociation(property) || context.isCollectionAssociation(property)) {
      lazyAssociationIsNotSerialised(entity, property, annotations, findings, source);
    }

    if (context.isSingleAssociation(property)) {
      annotations.add(0, manyToOne(entity, property));
      // The association owns the join column, so any [ForeignKey] name moves here.
      annotations = hoistJoinColumn(entity, property, annotations, context);
    } else if (context.isCollectionAssociation(property)) {
      annotations.add(0, oneToMany(entity, property, context, findings, source));
    } else if (context.isEnum(property.type().name())) {
      // Ordinals break the moment someone reorders the enum. Strings survive it.
      annotations.add(
          new JavaAnnotation(
              com.portway.core.rules.JavaType.of(JPA + ".Enumerated"),
              Map.of("value", "jakarta.persistence.EnumType.STRING")));
    } else if (context.isShadowForeignKey(entity, property)) {
      // The association owns the join column. A [ForeignKey] attribute written on
      // the scalar would otherwise emit @JoinColumn here as well, and Hibernate
      // rejects the same column being mapped twice for writing.
      annotations.removeIf(a -> a.simpleName().equals("JoinColumn"));
      annotations.add(readOnlyShadowColumn(property, annotations));
    }

    return JavaPoetTypes.toAnnotationSpecs(attributeMapper.merge(annotations));
  }

  /**
   * EF treats a property named {@code Id} or {@code <Type>Id} as the key without any attribute, and
   * generates its value on insert when it is numeric or a Guid. JPA needs both said explicitly:
   * without {@code @Id} the application fails at startup, which compiling alone cannot catch.
   */
  private void conventionKey(PropertyDecl property, List<JavaAnnotation> annotations) {
    boolean hasId = annotations.stream().anyMatch(a -> a.simpleName().equals("Id"));
    if (!hasId) {
      annotations.add(0, JavaAnnotation.marker(JPA + ".Id"));
    }
    boolean generationDecided =
        property.hasAttribute("DatabaseGenerated")
            || annotations.stream().anyMatch(a -> a.simpleName().equals("GeneratedValue"));
    if (generationDecided) {
      return;
    }
    String type = property.type().name();
    String strategy =
        switch (type) {
          case "int", "long", "short" -> "IDENTITY";
          case "Guid" -> "UUID";
          default -> null;
        };
    if (strategy != null) {
      annotations.add(
          1,
          new JavaAnnotation(
              JavaType.of(JPA + ".GeneratedValue"),
              Map.of("strategy", "jakarta.persistence.GenerationType." + strategy)));
    }
  }

  /**
   * A lazy association serialised to JSON after the session has closed throws, and one that is
   * loaded can recurse back through its inverse forever. EF, by contrast, writes whatever happened
   * to be loaded. The association is kept out of JSON, and the reviewer is told, because an API
   * client that relied on the field will notice.
   */
  private void lazyAssociationIsNotSerialised(
      TypeDecl entity,
      PropertyDecl property,
      List<JavaAnnotation> annotations,
      List<Finding> findings,
      SourceFile source) {
    annotations.add(JavaAnnotation.marker("com.fasterxml.jackson.annotation.JsonIgnore"));
    findings.add(
        Finding.at(
            FindingCode.LAZY_ASSOCIATION,
            entity.name() + "." + property.name() + " is a lazy JPA association and is left out of"
                + " JSON (@JsonIgnore). EF serialised whatever was loaded; if API clients read this"
                + " field, return a DTO that includes it.",
            source.path(),
            0));
  }

  private JavaAnnotation manyToOne(TypeDecl entity, PropertyDecl property) {
    return new JavaAnnotation(
        JavaType.of(JPA + ".ManyToOne"), Map.of("fetch", "jakarta.persistence.FetchType.LAZY"));
  }

  /**
   * Moves the {@code [ForeignKey("AuthorId")]} join column from the scalar property onto the
   * association, which is where JPA expects it.
   */
  private List<JavaAnnotation> hoistJoinColumn(
      TypeDecl entity,
      PropertyDecl property,
      List<JavaAnnotation> annotations,
      GenerationContext context) {

    boolean alreadyJoined =
        annotations.stream().anyMatch(a -> a.simpleName().equals("JoinColumn"));
    if (alreadyJoined) {
      return annotations;
    }

    Optional<String> foreignKeyColumn =
        entity.properties().stream()
            .filter(p -> p.name().equals(property.name() + "Id"))
            .findFirst()
            .map(p -> AttributeMapper.snakeCase(p.name()));

    List<JavaAnnotation> result = new ArrayList<>(annotations);
    foreignKeyColumn.ifPresent(
        column ->
            result.add(
                JavaAnnotation.of(JPA + ".JoinColumn", "name", "\"" + column + "\"")));
    return result;
  }

  private JavaAnnotation oneToMany(
      TypeDecl entity,
      PropertyDecl property,
      GenerationContext context,
      List<Finding> findings,
      SourceFile source) {

    String element = context.collectionElement(property);
    Optional<String> mappedBy = context.inverseFieldName(element, entity.name());

    Map<String, String> members = new LinkedHashMap<>();
    mappedBy.ifPresent(name -> members.put("mappedBy", "\"" + name + "\""));
    members.put("fetch", "jakarta.persistence.FetchType.LAZY");

    if (mappedBy.isEmpty()) {
      findings.add(
          Finding.at(
              FindingCode.EF_FLUENT_CONFIG,
              entity.name()
                  + "."
                  + property.name()
                  + " is a collection of "
                  + element
                  + " with no inverse property, so JPA will default to a join table. "
                  + "Confirm this matches the EF mapping.",
              source.path(),
              0));
    }
    return new JavaAnnotation(JavaType.of(JPA + ".OneToMany"), members);
  }

  /**
   * An EF model may expose both {@code Author Author} and {@code long AuthorId}. In JPA the
   * association owns the column, so the scalar becomes read-only or Hibernate rejects the duplicate
   * mapping outright.
   */
  private JavaAnnotation readOnlyShadowColumn(
      PropertyDecl property, List<JavaAnnotation> existing) {
    Map<String, String> members = new LinkedHashMap<>();
    members.put("name", "\"" + AttributeMapper.snakeCase(property.name()) + "\"");
    members.put("insertable", "false");
    members.put("updatable", "false");
    return new JavaAnnotation(JavaType.of(JPA + ".Column"), members);
  }

  /** The property carrying {@code [Key]}, or failing that the {@code Id} naming convention. */
  private boolean isIdentifier(TypeDecl entity, PropertyDecl property) {
    if (property.hasAttribute("Key")) {
      return true;
    }
    boolean anyExplicitKey = entity.properties().stream().anyMatch(p -> p.hasAttribute("Key"));
    return !anyExplicitKey
        && (property.name().equals("Id") || property.name().equals(entity.name() + "Id"));
  }

  private MethodSpec getter(String fieldName, TypeName fieldType, JavaType javaType) {
    boolean primitiveBoolean = javaType.primitive() && javaType.simpleName().equals("boolean");
    return MethodSpec.methodBuilder(Names.getterName(fieldName, primitiveBoolean))
        .addModifiers(Modifier.PUBLIC)
        .returns(fieldType)
        .addStatement("return $N", fieldName)
        .build();
  }

  private MethodSpec setter(String fieldName, TypeName fieldType) {
    return MethodSpec.methodBuilder(Names.setterName(fieldName))
        .addModifiers(Modifier.PUBLIC)
        .addParameter(fieldType, fieldName)
        .addStatement("this.$N = $N", fieldName, fieldName)
        .build();
  }

  /**
   * A C# expression-bodied property such as {@code public bool InStock => StockCount > 0;} has no
   * backing column, so it becomes a {@code @Transient} getter with no field behind it.
   */
  private MethodSpec computedGetter(PropertyDecl property, GenerationContext context) {
    MappedType mapped = typeMapper.map(property.type());
    boolean primitiveBoolean =
        mapped.type().primitive() && mapped.type().simpleName().equals("boolean");

    return MethodSpec.methodBuilder(
            Names.getterName(Names.fieldName(property.name()), primitiveBoolean))
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(ClassName.get(JPA, "Transient"))
        .returns(JavaPoetTypes.toTypeName(mapped.type(), context.entityPackage()))
        .addStatement("return $L", rewriteMemberReferences(property.getterExpression()))
        .build();
  }

  /**
   * Rewrites PascalCase member references in a short expression onto Java field names, so {@code
   * StockCount > 0} becomes {@code stockCount > 0}.
   *
   * <p>Deliberately narrow: it skips anything followed by {@code (}, which is a method call, and
   * anything inside a string literal. It is applied only to expression-bodied property bodies,
   * which are single expressions by definition, and is not a substitute for the BodyRewriter.
   */
  static String rewriteMemberReferences(String expression) {
    StringBuilder out = new StringBuilder();
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\"[^\"]*\"|\\b[A-Z][A-Za-z0-9]*\\b(?!\\s*\\()")
            .matcher(expression);
    while (matcher.find()) {
      String token = matcher.group();
      String replacement = token.startsWith("\"") ? token : Names.fieldName(token);
      matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private void note(
      List<Finding> findings,
      List<FindingCode> codes,
      TypeDecl type,
      SourceFile source,
      String member) {
    for (FindingCode code : codes) {
      String where = member == null ? type.name() : type.name() + "." + member;
      findings.add(Finding.at(code, messageFor(code, where), source.path(), 0));
    }
  }

  static String messageFor(FindingCode code, String where) {
    return switch (code) {
      case DECIMAL_ARITHMETIC ->
          where + " uses decimal, mapped to BigDecimal. Arithmetic operators do not carry over.";
      case NULLABLE_REFERENCE ->
          where + " was a nullable reference type; Java does not enforce this.";
      case UNSIGNED_BYTE -> where + " uses an unsigned C# type; Java's is signed.";
      case ASYNC_DROPPED -> where + " was asynchronous; the generated code is synchronous.";
      default -> where + ": " + code.name();
    };
  }
}
