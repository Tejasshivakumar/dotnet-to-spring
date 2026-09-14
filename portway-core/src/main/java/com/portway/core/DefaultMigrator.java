package com.portway.core;

import com.portway.core.classify.ClassifierChain;
import com.portway.core.classify.EntityClassifier;
import com.portway.core.generate.EntityGenerator;
import com.portway.core.generate.EnumGenerator;
import com.portway.core.generate.GeneratedFile;
import com.portway.core.generate.GenerationContext;
import com.portway.core.generate.JavaFormatter;
import com.portway.core.generate.MigrationOptions;
import com.portway.core.generate.Names;
import com.portway.core.generate.RepositoryGenerator;
import com.portway.core.ir.ClassRole;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import com.portway.core.ir.TypeKind;
import com.portway.core.parse.ProjectLoader;
import com.portway.core.parse.SyntaxError;
import com.portway.core.report.Finding;
import com.portway.core.report.FindingCode;
import com.portway.core.rules.AttributeMapper;
import com.portway.core.rules.TypeMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wires parse, classify and generate together.
 *
 * <p>Generation runs in dependency order — entities before the repositories that reference them —
 * and the resulting map preserves it, because that order is what the review UI lists and what a
 * golden diff reads as.
 */
public class DefaultMigrator implements Migrator {

  private final ProjectLoader loader = new ProjectLoader();
  private final ClassifierChain classifiers = ClassifierChain.defaults();
  private final TypeMapper typeMapper = TypeMapper.fromDefaults();
  private final AttributeMapper attributeMapper = new AttributeMapper();
  private final JavaFormatter formatter = new JavaFormatter();
  private final MigrationOptions options;

  public DefaultMigrator() {
    this(MigrationOptions.defaults());
  }

  public DefaultMigrator(MigrationOptions options) {
    this.options = options;
  }

  @Override
  public MigrationResult migrate(Path sourceDir) {
    ProjectLoader.Load load = loader.load(sourceDir);
    SourceProject project = classifiers.classify(load.project());

    Map<String, String> files = new LinkedHashMap<>();
    List<Finding> findings = new ArrayList<>(syntaxFindings(load.errors()));

    GenerationContext context = GenerationContext.from(project, basePackage(project));

    EntityGenerator entityGenerator =
        new EntityGenerator(typeMapper, attributeMapper, formatter);
    EnumGenerator enumGenerator = new EnumGenerator(formatter);
    RepositoryGenerator repositoryGenerator = new RepositoryGenerator(typeMapper, formatter);

    List<TypeDecl> entities = project.typesWithRole(ClassRole.ENTITY).toList();

    // 1. Enums first. They carry no role, but an entity referencing one does not
    //    compile without it, which makes them a hard dependency rather than a
    //    nice-to-have.
    for (SourceFile file : project.files()) {
      for (TypeDecl type : file.types()) {
        if (type.kind() == TypeKind.ENUM) {
          collect(enumGenerator.generate(type, file, context.entityPackage()), files, findings);
        }
      }
    }

    // 2. Entities, because everything after this references them.
    for (SourceFile file : project.files()) {
      for (TypeDecl type : file.types()) {
        if (type.role() == ClassRole.ENTITY) {
          collect(entityGenerator.generate(type, file, context), files, findings);
        }
      }
    }

    // 3. Repositories, one per DbSet on each DbContext.
    for (SourceFile file : project.files()) {
      for (TypeDecl type : file.types()) {
        if (type.role() == ClassRole.DB_CONTEXT) {
          repositoryGenerator
              .generate(
                  type, file, entities, context.repositoryPackage(), context.entityPackage())
              .forEach(generated -> collect(generated, files, findings));
          findings.addAll(fluentConfigurationFindings(type, file));
        }
      }
    }

    return MigrationResult.of(files, findings);
  }

  private void collect(
      GeneratedFile generated, Map<String, String> files, List<Finding> findings) {
    files.put(generated.path(), generated.content());
    findings.addAll(generated.findings());
  }

  private void collect(
      List<GeneratedFile> generated, Map<String, String> files, List<Finding> findings) {
    generated.forEach(file -> collect(file, files, findings));
  }

  /**
   * The base package: whatever the caller asked for, else the C# root namespace lowercased.
   *
   * <p>Taken from the namespace of the first entity rather than the first file, so a project whose
   * alphabetically-first file sits in some utility namespace still gets a sensible root.
   */
  private String basePackage(SourceProject project) {
    if (options.basePackage() != null && !options.basePackage().isBlank()) {
      return options.basePackage();
    }
    return project.files().stream()
        .filter(f -> f.types().stream().anyMatch(t -> t.role() == ClassRole.ENTITY))
        .map(SourceFile::namespaceName)
        .findFirst()
        .or(() -> project.files().stream().map(SourceFile::namespaceName).findFirst())
        .map(Names::basePackage)
        .orElse("migrated");
  }

  private List<Finding> syntaxFindings(List<SyntaxError> errors) {
    return errors.stream()
        .map(
            e ->
                new Finding(
                    com.portway.core.report.Severity.HIGH,
                    FindingCode.UNSUPPORTED_CONSTRUCT,
                    "Could not parse: " + e.message(),
                    e.path(),
                    e.line(),
                    null,
                    null))
        .toList();
  }

  /**
   * EF fluent configuration is not translated. Index definitions, cascade rules and key
   * conventions written in OnModelCreating have JPA equivalents that depend on intent, and guessing
   * at them produces a schema that looks right and behaves differently.
   */
  private List<Finding> fluentConfigurationFindings(TypeDecl dbContext, SourceFile file) {
    return dbContext.methods().stream()
        .filter(m -> m.name().equals("OnModelCreating") && m.hasBody())
        .map(
            m ->
                Finding.at(
                    FindingCode.EF_FLUENT_CONFIG,
                    dbContext.name()
                        + ".OnModelCreating contains fluent configuration that was not translated. "
                        + "Review it against the generated entities.",
                    file.path(),
                    m.startLine()))
        .toList();
  }

  /** Entity names discovered from DbSets, exposed for callers that want the plan before running. */
  public static List<String> entityNames(SourceProject project) {
    return List.copyOf(EntityClassifier.entityNames(project));
  }
}
