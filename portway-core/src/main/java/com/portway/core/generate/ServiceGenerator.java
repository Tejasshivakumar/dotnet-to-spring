package com.portway.core.generate;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.portway.core.ir.ClassRole;
import com.portway.core.ir.MethodDecl;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import com.portway.core.ir.TypeKind;
import com.portway.core.ir.TypeRef;
import com.portway.core.report.Finding;
import com.portway.core.report.FindingCode;
import com.portway.core.report.MethodReport;
import com.portway.core.rules.TypeMapper;
import com.portway.core.rules.body.RewriteContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Modifier;

/**
 * Generates service interfaces and their implementations.
 *
 * <p>By default the C# {@code I} prefix is dropped, as Java convention expects: {@code IBookService}
 * becomes the interface {@code BookService} and the implementation becomes {@code
 * BookServiceImpl}. {@link MigrationOptions#keepInterfacePrefix()} turns that off.
 *
 * <p>An implementation that used an EF DbContext becomes {@code @Transactional}. EF's unit of work
 * spans a request and commits on SaveChanges; a transaction per service call is the closest Spring
 * equivalent, and it is what makes dropping the SaveChanges calls correct.
 */
public class ServiceGenerator {

  private static final ClassName SERVICE = ClassName.get("org.springframework.stereotype", "Service");
  private static final ClassName TRANSACTIONAL =
      ClassName.get("org.springframework.transaction.annotation", "Transactional");

  private final TypeMapper typeMapper;
  private final MethodEmitter emitter;
  private final JavaFormatter formatter;

  public ServiceGenerator(TypeMapper typeMapper, MethodEmitter emitter, JavaFormatter formatter) {
    this.typeMapper = typeMapper;
    this.emitter = emitter;
    this.formatter = formatter;
  }

  /** A file plus the per-method reports behind it. */
  public record Result(GeneratedFile file, List<MethodReport> methods) {}

  public Result generateInterface(TypeDecl type, SourceFile source, GenerationContext context) {
    String javaName = context.javaName(type.name());
    String packageName = context.packageOf(type.name());
    String path = GenerationContext.javaPath(packageName, javaName);
    List<Finding> findings = new ArrayList<>();

    TypeSpec.Builder builder = TypeSpec.interfaceBuilder(javaName).addModifiers(Modifier.PUBLIC);
    String doc = Javadoc.fromXmlDoc(type.docComment());
    if (doc != null) {
      builder.addJavadoc("$L\n", doc);
    }
    for (TypeRef base : type.baseTypes()) {
      builder.addSuperinterface(emitter.javaType(base, context, true));
    }
    List<MethodReport> reports = new ArrayList<>();
    for (MethodDecl method : type.methods()) {
      MethodEmitter.Shape shape = emitter.defaultShape(method, context, !method.hasBody());
      MethodEmitter.Emitted emitted =
          emitter.emit(
              type, method, source, shape, RewriteContext.empty(), MigrationOptions.defaults(), path,
              javaName, List.of(), true);
      builder.addMethod(emitted.spec());
      findings.addAll(emitted.findings());
    }
    if (!type.properties().isEmpty()) {
      findings.add(
          Finding.at(
              FindingCode.UNSUPPORTED_CONSTRUCT,
              type.name() + " declares properties, which were not migrated onto the interface.",
              source.path(),
              0));
    }
    return new Result(write(packageName, builder.build(), path, source, findings), reports);
  }

  public Result generateImplementation(
      TypeDecl type,
      SourceFile source,
      SourceProject project,
      GenerationContext context,
      ProjectFacts facts,
      MigrationOptions options) {

    String javaName = context.javaName(type.name());
    String packageName = context.packageOf(type.name());
    String path = GenerationContext.javaPath(packageName, javaName);
    List<Finding> findings = new ArrayList<>();

    TypeSpec.Builder builder =
        TypeSpec.classBuilder(javaName).addModifiers(Modifier.PUBLIC).addAnnotation(SERVICE);
    String doc = Javadoc.fromXmlDoc(type.docComment());
    if (doc != null) {
      builder.addJavadoc("$L\n", doc);
    }

    List<TypeDecl> implemented = new ArrayList<>();
    for (TypeRef base : type.baseTypes()) {
      TypeDecl baseDecl = context.typesByName().get(base.name());
      TypeName javaBase = emitter.javaType(base, context, true);
      if (baseDecl != null && baseDecl.kind() == TypeKind.INTERFACE) {
        builder.addSuperinterface(javaBase);
        implemented.add(baseDecl);
      } else if (baseDecl != null) {
        builder.superclass(javaBase);
      } else if (base.name().startsWith("I") && base.name().length() > 1
          && Character.isUpperCase(base.name().charAt(1))) {
        findings.add(
            Finding.at(
                FindingCode.UNSUPPORTED_CONSTRUCT,
                type.name() + " implements " + base.name() + ", which is not part of the migrated"
                    + " project and was dropped.",
                source.path(),
                0));
      } else {
        findings.add(
            Finding.at(
                FindingCode.UNSUPPORTED_CONSTRUCT,
                type.name() + " extends " + base.name() + ", which is not part of the migrated"
                    + " project and was dropped.",
                source.path(),
                0));
      }
    }

    Dependencies.Plan deps =
        Dependencies.plan(type, javaName, packageName, source, project, context, typeMapper);
    findings.addAll(deps.findings());
    if (!deps.dbSets().isEmpty()) {
      builder.addAnnotation(AnnotationSpec.builder(TRANSACTIONAL).build());
    }
    deps.fields().forEach(builder::addField);
    if (deps.constructor() != null) {
      builder.addMethod(deps.constructor());
    }

    RewriteContext rewriteContext = rewriteContext(type, deps, facts, Map.of());
    List<MethodReport> reports = new ArrayList<>();
    for (MethodDecl method : type.methods()) {
      MethodEmitter.Shape shape = emitter.defaultShape(method, context, false);
      if (implementsMethod(implemented, method)) {
        List<AnnotationSpec> annotations = new ArrayList<>(shape.annotations());
        annotations.add(0, AnnotationSpec.builder(Override.class).build());
        shape =
            new MethodEmitter.Shape(
                shape.javaName(), shape.returnType(), shape.params(), shape.varargs(), annotations,
                shape.modifiers(), shape.notes());
      }
      MethodEmitter.Emitted emitted =
          emitter.emit(
              type, method, source, shape, rewriteContext, options, path, javaName,
              deps.fieldDeclarations(), false);
      builder.addMethod(emitted.spec());
      findings.addAll(emitted.findings());
      reports.add(emitted.report());
    }
    if (!type.properties().isEmpty()) {
      findings.add(
          Finding.at(
              FindingCode.UNSUPPORTED_CONSTRUCT,
              type.name() + " declares properties, which are not migrated on services.",
              source.path(),
              0));
    }

    return new Result(write(packageName, builder.build(), path, source, findings), reports);
  }

  /** The rewrite context for a class with injected dependencies: services and controllers. */
  static RewriteContext rewriteContext(
      TypeDecl type, Dependencies.Plan deps, ProjectFacts facts, Map<String, String> actionRoutes) {
    Map<String, String> ownMethods = new LinkedHashMap<>();
    for (MethodDecl m : type.methods()) {
      ownMethods.put(m.name(), Names.methodName(m.name()));
    }
    return new RewriteContext(
        deps.fieldRenames(),
        deps.dbSets(),
        facts.properties(),
        facts.typeNames(),
        ownMethods,
        facts.decimalMembers(),
        actionRoutes,
        false,
        false,
        deps.loggerFields(),
        facts.entityKeys());
  }

  private static boolean implementsMethod(List<TypeDecl> interfaces, MethodDecl method) {
    return interfaces.stream()
        .flatMap(i -> i.methods().stream())
        .anyMatch(m -> m.name().equals(method.name()) && m.params().size() == method.params().size());
  }

  private GeneratedFile write(
      String packageName, TypeSpec type, String path, SourceFile source, List<Finding> findings) {
    JavaFile javaFile = JavaFile.builder(packageName, type).skipJavaLangImports(true).indent("  ").build();
    return new GeneratedFile(path, formatter.format(javaFile.toString()), source.path(), findings);
  }

  static boolean isService(TypeDecl type) {
    return type.role() == ClassRole.SERVICE;
  }
}
