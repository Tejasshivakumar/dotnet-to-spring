package com.portway.core.generate;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.portway.core.ir.PropertyDecl;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.TypeDecl;
import com.portway.core.report.Finding;
import com.portway.core.report.FindingCode;
import com.portway.core.rules.JavaType;
import com.portway.core.rules.TypeMapper;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Modifier;

/**
 * Turns each {@code DbSet<T>} on a DbContext into a Spring Data repository interface.
 *
 * <p>The identifier type is read from the entity's {@code [Key]} property and always boxed, because
 * {@code JpaRepository<Book, long>} does not compile and, more subtly, because Spring Data uses a
 * null id to mean "not yet saved".
 *
 * <p>Fluent configuration in {@code OnModelCreating} is not translated. It is copied verbatim into
 * the migration notes with a HIGH finding, because guessing at index and cascade semantics is worse
 * than telling a human exactly what needs doing.
 */
public class RepositoryGenerator {

  private static final ClassName JPA_REPOSITORY =
      ClassName.get("org.springframework.data.jpa.repository", "JpaRepository");

  private final TypeMapper typeMapper;
  private final JavaFormatter formatter;

  public RepositoryGenerator(TypeMapper typeMapper, JavaFormatter formatter) {
    this.typeMapper = typeMapper;
    this.formatter = formatter;
  }

  /** One repository per DbSet on the context. */
  public List<GeneratedFile> generate(
      TypeDecl dbContext,
      SourceFile source,
      List<TypeDecl> entities,
      String repositoryPackage,
      String entityPackage) {

    List<GeneratedFile> generated = new ArrayList<>();

    for (PropertyDecl dbSet : dbContext.properties()) {
      if (!dbSet.type().name().equals("DbSet") || dbSet.type().typeArgs().isEmpty()) {
        continue;
      }
      String entityName = dbSet.type().typeArgs().get(0).name();
      TypeDecl entity =
          entities.stream().filter(e -> e.name().equals(entityName)).findFirst().orElse(null);

      generated.add(
          repository(entityName, entity, source, repositoryPackage, entityPackage));
    }
    return generated;
  }

  private GeneratedFile repository(
      String entityName,
      TypeDecl entity,
      SourceFile source,
      String repositoryPackage,
      String entityPackage) {

    List<Finding> findings = new ArrayList<>();
    TypeName idType = identifierType(entityName, entity, findings, source);

    TypeSpec repository =
        TypeSpec.interfaceBuilder(entityName + "Repository")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(
                ParameterizedTypeName.get(
                    JPA_REPOSITORY, ClassName.get(entityPackage, entityName), idType))
            .addJavadoc("Spring Data repository for {@link $L}.\n", entityName)
            .build();

    JavaFile javaFile =
        JavaFile.builder(repositoryPackage, repository)
            .skipJavaLangImports(true)
            .indent("  ")
            .build();

    String path =
        "src/main/java/"
            + repositoryPackage.replace('.', '/')
            + "/"
            + entityName
            + "Repository.java";

    return new GeneratedFile(
        path, formatter.format(javaFile.toString()), source.path(), findings);
  }

  /**
   * The boxed Java type of the entity's {@code [Key]} property, defaulting to Long when the entity
   * is missing or has no identifiable key.
   */
  private TypeName identifierType(
      String entityName, TypeDecl entity, List<Finding> findings, SourceFile source) {

    if (entity == null) {
      findings.add(
          Finding.at(
              FindingCode.UNSUPPORTED_CONSTRUCT,
              "DbSet<" + entityName + "> has no matching entity class; assumed a Long identifier.",
              source.path(),
              0));
      return ClassName.get(Long.class);
    }

    PropertyDecl key =
        entity.properties().stream()
            .filter(p -> p.hasAttribute("Key"))
            .findFirst()
            .orElseGet(
                () ->
                    entity.properties().stream()
                        .filter(p -> p.name().equals("Id") || p.name().equals(entityName + "Id"))
                        .findFirst()
                        .orElse(null));

    if (key == null) {
      findings.add(
          Finding.at(
              FindingCode.UNSUPPORTED_CONSTRUCT,
              entityName + " has no [Key] property and no Id convention; assumed a Long identifier.",
              source.path(),
              0));
      return ClassName.get(Long.class);
    }

    // forceBoxed: JpaRepository<Book, long> does not compile.
    JavaType mapped = typeMapper.map(key.type(), true).type();
    return JavaPoetTypes.toTypeName(mapped, "java.lang");
  }
}
