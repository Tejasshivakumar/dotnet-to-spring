package com.portway.core.generate;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.portway.core.ir.ClassRole;
import com.portway.core.ir.PropertyDecl;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.TypeDecl;
import com.portway.core.ir.TypeKind;
import com.portway.core.report.Finding;
import com.portway.core.report.FindingCode;
import com.portway.core.report.Severity;
import com.portway.core.rules.AttributeMapper;
import com.portway.core.rules.JavaAnnotation;
import com.portway.core.rules.JavaType;
import com.portway.core.rules.MappedType;
import com.portway.core.rules.TypeMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.lang.model.element.Modifier;

/**
 * Generates plain classes: DTOs, configuration classes, and types the classifier could not place.
 *
 * <p>Same shape as an entity — private fields, getters and setters, no Lombok — without the JPA
 * mapping. Persistence annotations derived from attributes such as {@code [StringLength]} are
 * dropped here: {@code @Column} on a request DTO means nothing, while its {@code @Size} does.
 */
public class PojoGenerator {

  private static final ClassName CONFIGURATION_PROPERTIES =
      ClassName.get("org.springframework.boot.context.properties", "ConfigurationProperties");

  private final TypeMapper typeMapper;
  private final AttributeMapper attributeMapper;
  private final JavaFormatter formatter;

  public PojoGenerator(
      TypeMapper typeMapper, AttributeMapper attributeMapper, JavaFormatter formatter) {
    this.typeMapper = typeMapper;
    this.attributeMapper = attributeMapper;
    this.formatter = formatter;
  }

  /**
   * @param configurationPrefix for a CONFIGURATION type bound to a config section, its Spring
   *     property prefix; null otherwise
   */
  public GeneratedFile generate(
      TypeDecl type, SourceFile source, GenerationContext context, String configurationPrefix) {
    List<Finding> findings = new ArrayList<>();
    String packageName = context.packageOf(type.name());

    TypeSpec.Builder builder = TypeSpec.classBuilder(type.name()).addModifiers(Modifier.PUBLIC);
    String doc = Javadoc.fromXmlDoc(type.docComment());
    if (doc != null) {
      builder.addJavadoc("$L\n", doc);
    }
    for (AnnotationSpec annotation : annotations(type.attributes(), type, null, source, findings)) {
      builder.addAnnotation(annotation);
    }
    if (configurationPrefix != null) {
      builder.addAnnotation(
          AnnotationSpec.builder(CONFIGURATION_PROPERTIES)
              .addMember("prefix", "$S", configurationPrefix)
              .build());
    }

    if (type.role() == ClassRole.UNKNOWN) {
      findings.add(
          Finding.at(
              FindingCode.UNCLASSIFIED_TYPE,
              type.name() + " did not match any role and was generated as a plain class. Check"
                  + " where it belongs.",
              source.path(),
              0));
    }
    if (type.kind() == TypeKind.STRUCT) {
      findings.add(
          Finding.at(
              FindingCode.UNSUPPORTED_CONSTRUCT,
              type.name() + " was a struct. Java classes are reference types: copies now alias.",
              source.path(),
              0));
    }
    if (!type.methods().isEmpty()) {
      findings.add(
          new Finding(
              Severity.MEDIUM,
              FindingCode.METHOD_STUBBED,
              type.name() + " has methods, which are not migrated on "
                  + type.role().name().toLowerCase(Locale.ROOT) + " types.",
              source.path(),
              type.methods().get(0).startLine(),
              null,
              null));
    }

    List<MethodSpec> accessors = new ArrayList<>();
    for (PropertyDecl property : type.properties()) {
      MappedType mapped = typeMapper.map(property.type());
      JavaType javaType = context.qualify(mapped.type());
      TypeName typeName = JavaPoetTypes.toTypeName(javaType, packageName);
      String fieldName = Names.fieldName(property.name());
      boolean primitiveBoolean = javaType.primitive() && javaType.simpleName().equals("boolean");

      if (property.isComputed()) {
        accessors.add(computedGetter(property, typeName, primitiveBoolean, source, findings, type));
        continue;
      }
      if (property.hasAccessorLogic()) {
        findings.add(
            Finding.at(
                FindingCode.UNSUPPORTED_CONSTRUCT,
                type.name() + "." + property.name() + " has accessor logic that was not migrated;"
                    + " it is now a plain field.",
                source.path(),
                0));
      }
      for (FindingCode note : mapped.notes()) {
        if (note != FindingCode.NULLABLE_REFERENCE) {
          findings.add(
              Finding.at(
                  note,
                  EntityGenerator.messageFor(note, type.name() + "." + property.name()),
                  source.path(),
                  0));
        }
      }

      FieldSpec.Builder field = FieldSpec.builder(typeName, fieldName, Modifier.PRIVATE);
      String propertyDoc = Javadoc.fromXmlDoc(property.docComment());
      if (propertyDoc != null) {
        field.addJavadoc("$L\n", propertyDoc);
      }
      annotations(property.attributes(), type, property, source, findings).forEach(field::addAnnotation);
      CodeBlock init = Initializers.translate(property.initializer(), javaType, context);
      if (init != null) {
        field.initializer(init);
      } else if (property.initializer() != null && !property.initializer().equals("null!")) {
        findings.add(
            new Finding(
                Severity.LOW,
                FindingCode.UNSUPPORTED_CONSTRUCT,
                type.name() + "." + property.name() + " initializer `" + property.initializer()
                    + "` was not translated; the field starts as its Java default.",
                source.path(),
                0,
                null,
                null));
      }
      builder.addField(field.build());

      accessors.add(
          MethodSpec.methodBuilder(Names.getterName(fieldName, primitiveBoolean))
              .addModifiers(Modifier.PUBLIC)
              .returns(typeName)
              .addStatement("return $N", fieldName)
              .build());
      if (property.hasSetter()) {
        accessors.add(
            MethodSpec.methodBuilder(Names.setterName(fieldName))
                .addModifiers(Modifier.PUBLIC)
                .addParameter(typeName, fieldName)
                .addStatement("this.$N = $N", fieldName, fieldName)
                .build());
      }
    }
    accessors.forEach(builder::addMethod);

    JavaFile javaFile =
        JavaFile.builder(packageName, builder.build()).skipJavaLangImports(true).indent("  ").build();
    return new GeneratedFile(
        GenerationContext.javaPath(packageName, type.name()),
        formatter.format(javaFile.toString()),
        source.path(),
        findings);
  }

  /** Attribute-derived annotations, minus the persistence mapping that only entities carry. */
  private List<AnnotationSpec> annotations(
      List<com.portway.core.ir.AttributeUse> attributes,
      TypeDecl type,
      PropertyDecl property,
      SourceFile source,
      List<Finding> findings) {
    AttributeMapper.Mapped mapped = attributeMapper.mapAll(attributes);
    for (FindingCode note : mapped.notes()) {
      String where = property == null ? type.name() : type.name() + "." + property.name();
      findings.add(Finding.at(note, where + ": " + note.name(), source.path(), 0));
    }
    List<JavaAnnotation> kept =
        mapped.annotations().stream()
            .filter(a -> !a.type().packageName().equals("jakarta.persistence"))
            .toList();
    return JavaPoetTypes.toAnnotationSpecs(attributeMapper.merge(kept));
  }

  private MethodSpec computedGetter(
      PropertyDecl property,
      TypeName typeName,
      boolean primitiveBoolean,
      SourceFile source,
      List<Finding> findings,
      TypeDecl type) {
    MethodSpec.Builder getter =
        MethodSpec.methodBuilder(Names.getterName(Names.fieldName(property.name()), primitiveBoolean))
            .addModifiers(Modifier.PUBLIC)
            .returns(typeName);
    String expression = property.getterExpression();
    if (expression != null) {
      return getter
          .addStatement("return $L", EntityGenerator.rewriteMemberReferences(expression))
          .build();
    }
    findings.add(
        Finding.at(
            FindingCode.METHOD_STUBBED,
            type.name() + "." + property.name() + " has a block getter that was not migrated.",
            source.path(),
            0));
    return getter
        .addJavadoc("MIGRATION: getter body not translated.\n<pre>\n$L\n</pre>\n",
            JavaCode.javadocEscape(property.getterBody()))
        .addStatement("throw new $T($S)", UnsupportedOperationException.class, "TODO: migrate from C#")
        .build();
  }
}
