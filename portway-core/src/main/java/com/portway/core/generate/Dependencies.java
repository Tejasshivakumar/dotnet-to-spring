package com.portway.core.generate;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.portway.core.ir.ClassRole;
import com.portway.core.ir.ConstructorDecl;
import com.portway.core.ir.FieldDecl;
import com.portway.core.ir.MethodDecl;
import com.portway.core.ir.ParamDecl;
import com.portway.core.ir.PropertyDecl;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import com.portway.core.ir.TypeRef;
import com.portway.core.report.Finding;
import com.portway.core.report.FindingCode;
import com.portway.core.report.Severity;
import com.portway.core.rules.TypeMapper;
import com.portway.core.rules.body.RewriteContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Modifier;

/**
 * Turns a C# class's constructor-injected dependencies into Java constructor injection.
 *
 * <p>Constructor injection only: no field injection, no {@code @Autowired}. It keeps dependencies
 * explicit, lets the fields be final, and keeps the class testable without a container.
 *
 * <p>Three dependencies change shape on the way across. An EF DbContext becomes one Spring Data
 * repository per DbSet the class actually uses. An {@code ILogger<T>} becomes a static SLF4J
 * logger, which is how Java code gets one. An {@code IOptions<T>} becomes the options type itself,
 * which Spring binds directly.
 */
public final class Dependencies {

  private static final ClassName LOGGER = ClassName.get("org.slf4j", "Logger");
  private static final ClassName LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");
  private static final Pattern ASSIGNMENT =
      Pattern.compile(
          "^(?:this\\.)?(\\w+)\\s*=\\s*(\\w+)(?:\\.(?:Value|CurrentValue))?\\s*"
              + "(?:\\?\\?\\s*throw\\s+new\\s+\\w+\\s*\\([^;]*\\))?\\s*;$");

  /**
   * The plan.
   *
   * @param fieldRenames C# field name to Java field name, for the body rewriter
   * @param dbSets per DbContext field, DbSet property to entity, for the body rewriter
   * @param loggerFields C# fields holding a logger
   * @param fieldDeclarations the Java fields as declarations, for the LLM prompt
   */
  public record Plan(
      List<FieldSpec> fields,
      MethodSpec constructor,
      Map<String, String> fieldRenames,
      Map<String, Map<String, String>> dbSets,
      Set<String> loggerFields,
      List<String> fieldDeclarations,
      List<Finding> findings) {}

  private Dependencies() {}

  public static Plan plan(
      TypeDecl type,
      String javaClassName,
      String javaPackage,
      SourceFile source,
      SourceProject project,
      GenerationContext context,
      TypeMapper typeMapper) {

    List<Finding> findings = new ArrayList<>();
    List<FieldSpec> fields = new ArrayList<>();
    Map<String, String> renames = new LinkedHashMap<>();
    Map<String, Map<String, String>> dbSets = new LinkedHashMap<>();
    Set<String> loggers = new LinkedHashSet<>();
    List<String> declarations = new ArrayList<>();

    ConstructorDecl constructor =
        type.constructors().stream()
            .max(Comparator.comparingInt(c -> c.params().size()))
            .orElse(null);
    if (type.constructors().size() > 1) {
      findings.add(
          Finding.at(
              FindingCode.UNSUPPORTED_CONSTRUCT,
              type.name() + " has several constructors; only the widest was migrated for injection.",
              source.path(),
              0));
    }

    // Which constructor parameter feeds which field.
    Map<String, String> paramToField = new LinkedHashMap<>();
    boolean constructorLogic = false;
    if (constructor != null && constructor.bodyRaw() != null) {
      String body = constructor.bodyRaw().strip();
      body = body.substring(1, body.length() - 1);
      for (String statement : body.split("(?<=;)")) {
        String s = statement.strip();
        if (s.isEmpty()) {
          continue;
        }
        Matcher m = ASSIGNMENT.matcher(s);
        if (m.matches()) {
          paramToField.put(m.group(2), m.group(1));
        } else {
          constructorLogic = true;
        }
      }
    }
    if (constructorLogic) {
      findings.add(
          Finding.at(
              FindingCode.UNSUPPORTED_CONSTRUCT,
              type.name() + "'s constructor does more than assign dependencies; only the"
                  + " assignments were migrated.",
              source.path(),
              0));
    }

    Map<String, FieldDecl> fieldsByName = new LinkedHashMap<>();
    type.fields().forEach(f -> fieldsByName.put(f.name(), f));

    MethodSpec.Builder ctor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
    boolean hasInjection = false;
    Set<String> injectedFields = new LinkedHashSet<>();

    if (constructor != null) {
      for (ParamDecl param : constructor.params()) {
        String csharpField = paramToField.get(param.name());
        String fieldName = csharpField == null ? param.name() : csharpField;
        injectedFields.add(fieldName);

        if (isDbContext(param.type(), project)) {
          Map<String, String> sets = dbSetsOf(param.type().name(), project);
          dbSets.put(fieldName, sets);
          for (String entity : usedEntities(type, fieldName, sets)) {
            String repoField = RewriteContext.repositoryField(entity);
            ClassName repoType = ClassName.get(context.repositoryPackage(), entity + "Repository");
            fields.add(FieldSpec.builder(repoType, repoField, Modifier.PRIVATE, Modifier.FINAL).build());
            declarations.add("private final " + repoType.simpleName() + " " + repoField);
            ctor.addParameter(repoType, repoField).addStatement("this.$N = $N", repoField, repoField);
            hasInjection = true;
          }
          continue;
        }

        if (isLogger(param.type())) {
          loggers.add(fieldName);
          renames.put(fieldName, "log");
          fields.add(
              FieldSpec.builder(LOGGER, "log", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                  .initializer("$T.getLogger($L.class)", LOGGER_FACTORY, javaClassName)
                  .build());
          declarations.add("private static final Logger log");
          continue;
        }

        TypeRef injected = param.type();
        if (injected.name().equals("IOptions") || injected.name().equals("IOptionsSnapshot")
            || injected.name().equals("IOptionsMonitor")) {
          injected = injected.typeArgs().isEmpty() ? injected : injected.typeArgs().get(0);
          renames.put(fieldName + ".Value", javaFieldName(fieldName));
          renames.put(fieldName + ".CurrentValue", javaFieldName(fieldName));
        }

        String javaField = javaFieldName(fieldName);
        TypeName javaType =
            JavaPoetTypes.toTypeName(
                context.qualify(typeMapper.map(injected, true).type()), context.modelPackage());
        renames.put(fieldName, javaField);
        fields.add(FieldSpec.builder(javaType, javaField, Modifier.PRIVATE, Modifier.FINAL).build());
        declarations.add("private final " + JavaCode.simpleNames(javaType) + " " + javaField);
        ctor.addParameter(javaType, javaField).addStatement("this.$N = $N", javaField, javaField);
        hasInjection = true;
      }
    }

    // Fields that were not injected: constants and state.
    Set<String> javaNames = new LinkedHashSet<>();
    fields.forEach(f -> javaNames.add(f.name()));
    for (FieldDecl field : type.fields()) {
      if (injectedFields.contains(field.name())) {
        continue;
      }
      String javaField = javaFieldName(field.name());
      if (javaNames.contains(javaField)) {
        findings.add(
            Finding.at(
                FindingCode.NAME_COLLISION,
                type.name() + "." + field.name() + " maps to Java field " + javaField + ", which an"
                    + " injected dependency already uses. It was not generated.",
                source.path(),
                0));
        continue;
      }
      renames.put(field.name(), javaField);
      com.portway.core.rules.JavaType mapped = context.qualify(typeMapper.map(field.type()).type());
      TypeName javaType = JavaPoetTypes.toTypeName(mapped, context.modelPackage());
      List<Modifier> modifiers = new ArrayList<>(List.of(Modifier.PRIVATE));
      if (field.modifiers().contains("static") || field.modifiers().contains("const")) {
        modifiers.add(Modifier.STATIC);
      }
      if (field.isReadonly() || field.modifiers().contains("const")) {
        modifiers.add(Modifier.FINAL);
      }
      FieldSpec.Builder spec = FieldSpec.builder(javaType, javaField, modifiers.toArray(Modifier[]::new));
      CodeBlock init = Initializers.translate(field.initializer(), mapped, context);
      if (init != null) {
        spec.initializer(init);
      } else if (field.initializer() != null) {
        findings.add(
            new Finding(
                Severity.MEDIUM,
                FindingCode.UNSUPPORTED_CONSTRUCT,
                type.name() + "." + field.name() + " has initializer `" + field.initializer()
                    + "`, which was not translated.",
                source.path(),
                0,
                null,
                null));
        if (modifiers.contains(Modifier.FINAL)) {
          // A final field with no initializer does not compile.
          spec = FieldSpec.builder(javaType, javaField, Modifier.PRIVATE);
        }
      }
      fields.add(spec.build());
      declarations.add(
          modifiers.stream().map(Modifier::toString).reduce((a, b) -> a + " " + b).orElse("")
              + " " + JavaCode.simpleNames(javaType) + " " + javaField);
    }

    return new Plan(
        fields,
        hasInjection ? ctor.build() : null,
        renames,
        dbSets,
        loggers,
        declarations,
        findings);
  }

  /** {@code _bookService} becomes {@code bookService}. */
  public static String javaFieldName(String csharpField) {
    String name = csharpField;
    while (name.startsWith("_")) {
      name = name.substring(1);
    }
    if (name.startsWith("m_")) {
      name = name.substring(2);
    }
    return name.isEmpty() ? csharpField : Names.fieldName(name);
  }

  static boolean isLogger(TypeRef type) {
    return type.name().equals("ILogger");
  }

  static boolean isDbContext(TypeRef type, SourceProject project) {
    return project
        .allTypes()
        .anyMatch(
            t -> t.name().equals(type.name())
                && (t.role() == ClassRole.DB_CONTEXT || t.hasBaseType("DbContext")));
  }

  /** DbSet property name to entity name, in declaration order. */
  static Map<String, String> dbSetsOf(String contextName, SourceProject project) {
    Map<String, String> sets = new LinkedHashMap<>();
    project
        .allTypes()
        .filter(t -> t.name().equals(contextName))
        .findFirst()
        .ifPresent(
            ctx -> {
              for (PropertyDecl p : ctx.properties()) {
                if (p.type().name().equals("DbSet") && !p.type().typeArgs().isEmpty()) {
                  sets.put(p.name(), p.type().typeArgs().get(0).name());
                }
              }
            });
    return sets;
  }

  /**
   * The entities whose DbSet the class's bodies reference through this field. Only those get a
   * repository injected: a service that reads books should not depend on the author repository.
   */
  static List<String> usedEntities(TypeDecl type, String field, Map<String, String> sets) {
    StringBuilder bodies = new StringBuilder();
    for (MethodDecl m : type.methods()) {
      if (m.bodyRaw() != null) {
        bodies.append(m.bodyRaw()).append('\n');
      }
    }
    for (PropertyDecl p : type.properties()) {
      if (p.getterBody() != null) {
        bodies.append(p.getterBody()).append('\n');
      }
    }
    List<String> used = new ArrayList<>();
    for (Map.Entry<String, String> set : sets.entrySet()) {
      Pattern reference =
          Pattern.compile("\\b" + Pattern.quote(field) + "\\s*\\.\\s*" + Pattern.quote(set.getKey()) + "\\b");
      if (reference.matcher(bodies).find() && !used.contains(set.getValue())) {
        used.add(set.getValue());
      }
    }
    return used;
  }
}
