package com.portway.core;

import com.portway.core.classify.ClassifierChain;
import com.portway.core.classify.ProgramScanner;
import com.portway.core.generate.ApplicationGenerator;
import com.portway.core.generate.ApplicationYmlGenerator;
import com.portway.core.generate.ControllerGenerator;
import com.portway.core.generate.EntityGenerator;
import com.portway.core.generate.EnumGenerator;
import com.portway.core.generate.GeneratedFile;
import com.portway.core.generate.GenerationContext;
import com.portway.core.generate.JavaFormatter;
import com.portway.core.generate.MethodEmitter;
import com.portway.core.generate.MigrationNotesGenerator;
import com.portway.core.generate.MigrationNotesGenerator.FluentConfiguration;
import com.portway.core.generate.MigrationOptions;
import com.portway.core.generate.Names;
import com.portway.core.generate.PojoGenerator;
import com.portway.core.generate.PomGenerator;
import com.portway.core.generate.ProjectFacts;
import com.portway.core.generate.RepositoryGenerator;
import com.portway.core.generate.ServiceGenerator;
import com.portway.core.ir.ClassRole;
import com.portway.core.ir.MethodDecl;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import com.portway.core.ir.TypeKind;
import com.portway.core.parse.ProjectLoader;
import com.portway.core.parse.SyntaxError;
import com.portway.core.report.Finding;
import com.portway.core.report.FindingCode;
import com.portway.core.report.MethodReport;
import com.portway.core.report.Severity;
import com.portway.core.rules.AttributeMapper;
import com.portway.core.rules.DependencyMapper;
import com.portway.core.rules.TypeMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One migration, run stage by stage.
 *
 * <p>{@link Migrator#migrate} runs everything in one call, which is what the CLI and the golden
 * tests want. The pipeline in the Spring application wants the stages separately, so it can report
 * progress after each and so the AI and repair stages can regenerate with new {@link
 * MigrationOptions} without parsing again. Parsing and classification happen once; generation is
 * cheap and deterministic, so it simply reruns with the updated options.
 */
public final class MigrationSession {

  private final Path sourceDir;
  private MigrationOptions options;

  private final ProjectLoader loader = new ProjectLoader();
  private final ClassifierChain classifiers = ClassifierChain.defaults();
  private final TypeMapper typeMapper = TypeMapper.fromDefaults();
  private final AttributeMapper attributeMapper = new AttributeMapper();
  private final DependencyMapper dependencyMapper = DependencyMapper.fromDefaults();
  private final JavaFormatter formatter = new JavaFormatter();

  private SourceProject parsed;
  private List<SyntaxError> syntaxErrors = List.of();
  private SourceProject project;
  private MigrationResult result;

  public MigrationSession(Path sourceDir, MigrationOptions options) {
    this.sourceDir = sourceDir;
    this.options = options == null ? MigrationOptions.defaults() : options;
  }

  // --------------------------------------------------------------- stages

  /** Reads every .cs file, the .csproj and appsettings.json into the IR. */
  public SourceProject parse() {
    ProjectLoader.Load load = loader.load(sourceDir);
    parsed = load.project();
    syntaxErrors = load.errors();
    return parsed;
  }

  /** Assigns every type its role. Needs the whole project, so it runs after parsing everything. */
  public SourceProject classify() {
    if (parsed == null) {
      parse();
    }
    project = classifiers.classify(parsed);
    return project;
  }

  /** Generates the Spring Boot project from the classified IR under the current options. */
  public MigrationResult generate() {
    if (project == null) {
      classify();
    }
    result = new Generation(project).run();
    return result;
  }

  /** Parse, classify and generate. */
  public MigrationResult run() {
    parse();
    classify();
    return generate();
  }

  public MigrationOptions options() {
    return options;
  }

  /** Replaces the options; the next {@link #generate()} uses them. */
  public void options(MigrationOptions next) {
    this.options = next;
  }

  public SourceProject project() {
    return project;
  }

  public MigrationResult result() {
    return result;
  }

  public List<SyntaxError> syntaxErrors() {
    return syntaxErrors;
  }

  // ----------------------------------------------------------- generation

  /** One generation pass. Separate from the session so its state cannot leak between passes. */
  private final class Generation {

    private final SourceProject project;
    private final GenerationContext context;
    private final ProjectFacts facts;
    private final MethodEmitter emitter = new MethodEmitter(typeMapper);

    private final List<GeneratedFile> enums = new ArrayList<>();
    private final List<GeneratedFile> domain = new ArrayList<>();
    private final List<GeneratedFile> repositories = new ArrayList<>();
    private final List<GeneratedFile> models = new ArrayList<>();
    private final List<GeneratedFile> serviceInterfaces = new ArrayList<>();
    private final List<GeneratedFile> services = new ArrayList<>();
    private final List<GeneratedFile> controllers = new ArrayList<>();
    private final List<MethodReport> methods = new ArrayList<>();
    private final List<Finding> findings = new ArrayList<>();
    private final List<FluentConfiguration> fluent = new ArrayList<>();
    private boolean usesSecurity;
    private boolean hasConfigurationProperties;

    Generation(SourceProject project) {
      this.project = project;
      String basePackage = basePackage(project);
      this.context = GenerationContext.from(project, basePackage, options);
      this.facts = ProjectFacts.of(project, typeMapper);
    }

    MigrationResult run() {
      findings.addAll(syntaxFindings());

      EntityGenerator entityGenerator = new EntityGenerator(typeMapper, attributeMapper, formatter);
      EnumGenerator enumGenerator = new EnumGenerator(formatter);
      RepositoryGenerator repositoryGenerator = new RepositoryGenerator(typeMapper, formatter);
      PojoGenerator pojoGenerator = new PojoGenerator(typeMapper, attributeMapper, formatter);
      ServiceGenerator serviceGenerator = new ServiceGenerator(typeMapper, emitter, formatter);
      ControllerGenerator controllerGenerator =
          new ControllerGenerator(typeMapper, attributeMapper, emitter, formatter);
      Map<String, String> sections = ProgramScanner.registrations(project).configurationSections();
      List<TypeDecl> entities = project.typesWithRole(ClassRole.ENTITY).toList();

      for (SourceFile file : project.files()) {
        for (TypeDecl type : file.types()) {
          if (type.kind() == TypeKind.ENUM) {
            // Enums first: they carry no role, but an entity referencing one does
            // not compile without it, which makes them a hard dependency.
            enums.add(enumGenerator.generate(type, file, context.entityPackage()));
            continue;
          }
          switch (type.role()) {
            case ENTITY -> domain.add(entityGenerator.generate(type, file, context));
            case DB_CONTEXT -> {
              repositories.addAll(
                  repositoryGenerator.generate(
                      type, file, entities, context.repositoryPackage(), context.entityPackage()));
              fluentConfiguration(type, file);
            }
            case SERVICE_INTERFACE -> serviceInterfaces.add(serviceGenerator.generateInterface(type, file, context).file());
            case SERVICE, REPOSITORY -> {
              ServiceGenerator.Result r =
                  serviceGenerator.generateImplementation(type, file, project, context, facts, options);
              services.add(r.file());
              methods.addAll(r.methods());
            }
            case CONTROLLER -> {
              ControllerGenerator.Result r =
                  controllerGenerator.generate(type, file, project, context, facts, options);
              controllers.add(r.file());
              methods.addAll(r.methods());
              usesSecurity |= r.usesSecurity();
            }
            case PROGRAM_ENTRY -> {
              // Program.cs becomes MigratedApplication, below.
            }
            case DTO, CONFIGURATION, UNKNOWN -> {
              if (type.kind() == TypeKind.INTERFACE) {
                findings.add(
                    Finding.at(
                        FindingCode.UNCLASSIFIED_TYPE,
                        "Interface " + type.name() + " did not match any role and was generated"
                            + " as a plain interface.",
                        file.path(),
                        0));
                serviceInterfaces.add(serviceGenerator.generateInterface(type, file, context).file());
              } else {
                String prefix = null;
                if (type.role() == ClassRole.CONFIGURATION && sections.containsKey(type.name())) {
                  prefix = "app." + ApplicationYmlGenerator.kebab(sections.get(type.name()));
                  hasConfigurationProperties = true;
                }
                models.add(pojoGenerator.generate(type, file, context, prefix));
              }
            }
          }
        }
      }

      List<GeneratedFile> code = new ArrayList<>();
      code.addAll(enums);
      code.addAll(domain);
      code.addAll(repositories);
      code.addAll(models);
      code.addAll(serviceInterfaces);
      code.addAll(services);
      code.addAll(controllers);

      boolean validation = code.stream().anyMatch(f -> f.content().contains("jakarta.validation"));
      GeneratedFile pom =
          new PomGenerator(dependencyMapper)
              .generate(
                  project,
                  context.basePackage(),
                  new PomGenerator.Needs(!entities.isEmpty(), validation, usesSecurity));
      GeneratedFile yml = new ApplicationYmlGenerator().generate(project);

      ApplicationGenerator applicationGenerator = new ApplicationGenerator(formatter);
      List<GeneratedFile> all = new ArrayList<>();
      all.add(pom);
      all.add(yml);
      all.addAll(code);
      if (usesSecurity) {
        all.add(applicationGenerator.securityConfig(context.configPackage()));
      }
      all.add(applicationGenerator.application(context.basePackage(), hasConfigurationProperties));

      for (GeneratedFile file : all) {
        for (Finding f : file.findings()) {
          findings.add(
              f.generatedPath() == null
                  ? new Finding(
                      f.severity(), f.code(), f.message(), f.sourcePath(), f.sourceLine(), file.path(),
                      f.generatedLine())
                  : f);
        }
      }
      all.add(new MigrationNotesGenerator().generate(project.name(), methods, findings, fluent));

      return MigrationResult.of(all, findings, methods);
    }

    private void fluentConfiguration(TypeDecl dbContext, SourceFile file) {
      for (MethodDecl m : dbContext.methods()) {
        if (m.name().equals("OnModelCreating") && m.hasBody()) {
          fluent.add(new FluentConfiguration(dbContext, file, m));
          findings.add(
              Finding.at(
                  FindingCode.EF_FLUENT_CONFIG,
                  dbContext.name() + ".OnModelCreating contains fluent configuration that was not"
                      + " translated. It is copied into MIGRATION-NOTES.md; review it against the"
                      + " generated entities.",
                  file.path(),
                  m.startLine()));
        }
      }
    }

    private List<Finding> syntaxFindings() {
      return syntaxErrors.stream()
          .map(
              e ->
                  new Finding(
                      Severity.HIGH,
                      FindingCode.UNSUPPORTED_CONSTRUCT,
                      (e.message().startsWith("Unsupported construct")
                              ? e.message()
                              : "Could not parse: " + e.message())
                          + ". The file was not migrated.",
                      relative(e.path()),
                      e.line(),
                      null,
                      null))
          .toList();
    }

    private String relative(String path) {
      try {
        Path p = Path.of(path);
        return p.isAbsolute() ? sourceDir.toAbsolutePath().relativize(p).toString().replace('\\', '/') : path;
      } catch (IllegalArgumentException e) {
        return path;
      }
    }
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
}
