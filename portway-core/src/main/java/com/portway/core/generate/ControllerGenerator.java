package com.portway.core.generate;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.WildcardTypeName;
import com.palantir.javapoet.TypeSpec;
import com.portway.core.ir.AttributeUse;
import com.portway.core.ir.MethodDecl;
import com.portway.core.ir.ParamDecl;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import com.portway.core.ir.TypeKind;
import com.portway.core.ir.TypeRef;
import com.portway.core.report.Finding;
import com.portway.core.report.FindingCode;
import com.portway.core.report.MethodReport;
import com.portway.core.rules.AttributeMapper;
import com.portway.core.rules.JavaAnnotation;
import com.portway.core.rules.TypeMapper;
import com.portway.core.rules.body.RewriteContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Modifier;

/**
 * Generates a {@code @RestController} from an ASP.NET Core controller.
 *
 * <p>Routing is the delicate part. ASP.NET's {@code [Route("api/[controller]")]} substitutes the
 * class name minus {@code Controller}; route constraints such as {@code {id:int}} have no Spring
 * counterpart in the path and are dropped; and a parameter with no binding attribute binds by
 * convention, which here is: in the route template, a path variable; a simple type, a query
 * parameter; anything else, the body.
 */
public class ControllerGenerator {

  private static final String WEB = "org.springframework.web.bind.annotation";
  private static final ClassName REST_CONTROLLER = ClassName.get(WEB, "RestController");
  private static final ClassName REQUEST_MAPPING = ClassName.get(WEB, "RequestMapping");
  private static final ClassName PATH_VARIABLE = ClassName.get(WEB, "PathVariable");
  private static final ClassName REQUEST_PARAM = ClassName.get(WEB, "RequestParam");
  private static final ClassName REQUEST_BODY = ClassName.get(WEB, "RequestBody");
  private static final ClassName REQUEST_HEADER = ClassName.get(WEB, "RequestHeader");
  private static final ClassName MODEL_ATTRIBUTE =
      ClassName.get("org.springframework.web.bind.annotation", "ModelAttribute");
  private static final ClassName VALID = ClassName.get("jakarta.validation", "Valid");
  private static final ClassName VALIDATED =
      ClassName.get("org.springframework.validation.annotation", "Validated");
  private static final ClassName PRE_AUTHORIZE =
      ClassName.get("org.springframework.security.access.prepost", "PreAuthorize");

  private static final Map<String, String> HTTP_METHODS =
      Map.of(
          "HttpGet", "GetMapping",
          "HttpPost", "PostMapping",
          "HttpPut", "PutMapping",
          "HttpDelete", "DeleteMapping",
          "HttpPatch", "PatchMapping");

  private static final Set<String> SIMPLE_TYPES =
      Set.of("int", "long", "short", "byte", "bool", "string", "decimal", "double", "float",
          "char", "Guid", "DateTime", "DateOnly", "DateTimeOffset", "TimeOnly", "uint", "ulong");

  private final TypeMapper typeMapper;
  private final AttributeMapper attributeMapper;
  private final MethodEmitter emitter;
  private final JavaFormatter formatter;

  public ControllerGenerator(
      TypeMapper typeMapper,
      AttributeMapper attributeMapper,
      MethodEmitter emitter,
      JavaFormatter formatter) {
    this.typeMapper = typeMapper;
    this.attributeMapper = attributeMapper;
    this.emitter = emitter;
    this.formatter = formatter;
  }

  /** A controller file, its method reports, and whether it needs Spring Security. */
  public record Result(GeneratedFile file, List<MethodReport> methods, boolean usesSecurity) {}

  public Result generate(
      TypeDecl type,
      SourceFile source,
      SourceProject project,
      GenerationContext context,
      ProjectFacts facts,
      MigrationOptions options) {

    String javaName = context.javaName(type.name());
    String packageName = context.controllerPackage();
    String path = GenerationContext.javaPath(packageName, javaName);
    List<Finding> findings = new ArrayList<>();
    boolean apiController = type.hasAttribute("ApiController");
    boolean usesSecurity = false;

    TypeSpec.Builder builder =
        TypeSpec.classBuilder(javaName).addModifiers(Modifier.PUBLIC).addAnnotation(REST_CONTROLLER);
    String doc = Javadoc.fromXmlDoc(type.docComment());
    if (doc != null) {
      builder.addJavadoc("$L\n", doc);
    }

    String baseRoute = classRoute(type);
    if (!baseRoute.isEmpty()) {
      builder.addAnnotation(AnnotationSpec.builder(REQUEST_MAPPING).addMember("value", "$S", baseRoute).build());
    }
    Optional<AnnotationSpec> classAuth = authorization(type.attributes(), type.name(), source, 0, findings);
    if (classAuth.isPresent()) {
      builder.addAnnotation(classAuth.get());
      usesSecurity = true;
    }

    for (TypeRef base : type.baseTypes()) {
      if (!base.name().equals("ControllerBase") && !base.name().equals("Controller")) {
        findings.add(
            Finding.at(
                FindingCode.UNSUPPORTED_CONSTRUCT,
                type.name() + " extends " + base.name() + "; the base type was dropped.",
                source.path(),
                0));
      }
    }

    Dependencies.Plan deps =
        Dependencies.plan(type, javaName, packageName, source, project, context, typeMapper);
    findings.addAll(deps.findings());
    deps.fields().forEach(builder::addField);
    if (deps.constructor() != null) {
      builder.addMethod(deps.constructor());
    }

    // Every action's full route, so CreatedAtAction(nameof(X), ...) can build a Location header.
    Map<String, String> actionRoutes = new LinkedHashMap<>();
    for (MethodDecl method : type.methods()) {
      httpAttribute(method).ifPresent(
          http -> actionRoutes.put(method.name(), joinRoutes(baseRoute, actionTemplate(method, http))));
    }

    RewriteContext classContext = ServiceGenerator.rewriteContext(type, deps, facts, actionRoutes);
    List<MethodReport> reports = new ArrayList<>();
    boolean anyConstraint = false;

    for (MethodDecl method : type.methods()) {
      MethodEmitter.Shape shape = emitter.defaultShape(method, context, false);
      Optional<AttributeUse> http = httpAttribute(method);
      RewriteContext rewriteContext = classContext;

      if (http.isPresent()) {
        String template = actionTemplate(method, http.get());
        List<AnnotationSpec> annotations = new ArrayList<>();
        AnnotationSpec.Builder mapping =
            AnnotationSpec.builder(ClassName.get(WEB, HTTP_METHODS.get(http.get().name())));
        if (!template.isEmpty()) {
          mapping.addMember("value", "$S", "/" + template);
        }
        annotations.add(mapping.build());
        Optional<AnnotationSpec> auth =
            authorization(method.attributes(), type.name() + "." + method.name(), source, method.startLine(), findings);
        if (auth.isPresent()) {
          annotations.add(auth.get());
          usesSecurity = true;
        }
        annotations.addAll(shape.annotations());

        Set<String> routeVariables = routeVariables(joinRoutes(baseRoute, template));
        List<ParameterSpec> params = new ArrayList<>();
        for (int i = 0; i < method.params().size(); i++) {
          ParamDecl param = method.params().get(i);
          ParameterSpec base = shape.params().get(i);
          ParameterSpec.Builder bound = base.toBuilder();
          bound.addAnnotations(binding(param, routeVariables, apiController, type, method, source, findings));
          List<AnnotationSpec> constraints = constraintAnnotations(param);
          anyConstraint |= !constraints.isEmpty();
          bound.addAnnotations(constraints);
          params.add(bound.build());
        }

        shape =
            new MethodEmitter.Shape(
                shape.javaName(), widenedReturnType(method, shape.returnType()), params,
                shape.varargs(), annotations, List.of(Modifier.PUBLIC), shape.notes());
        rewriteContext = classContext.withWrapBareReturns(returnsActionResultOfT(method.returnType()));
      }

      MethodEmitter.Emitted emitted =
          emitter.emit(
              type, method, source, shape, rewriteContext, options, path, javaName,
              deps.fieldDeclarations(), false);
      builder.addMethod(emitted.spec());
      findings.addAll(emitted.findings());
      reports.add(emitted.report());
    }
    if (anyConstraint) {
      // Constraint annotations on controller parameters are only enforced on a @Validated bean.
      builder.addAnnotation(VALIDATED);
    }

    JavaFile javaFile =
        JavaFile.builder(packageName, builder.build()).skipJavaLangImports(true).indent("  ").build();
    return new Result(
        new GeneratedFile(path, formatter.format(javaFile.toString()), source.path(), findings),
        reports,
        usesSecurity);
  }

  // ------------------------------------------------------------- routing

  /** {@code [Route("api/[controller]")]} on BooksController becomes {@code /api/books}. */
  static String classRoute(TypeDecl type) {
    return type.attributes().stream()
        .filter(a -> a.name().equals("Route"))
        .findFirst()
        .flatMap(AttributeUse::firstArgUnquoted)
        .map(route -> {
          String controller = type.name().endsWith("Controller")
              ? type.name().substring(0, type.name().length() - "Controller".length())
              : type.name();
          return normalise(route.replace("[controller]", controller.toLowerCase(Locale.ROOT)));
        })
        .map(route -> route.isEmpty() ? "" : "/" + route)
        .orElse("");
  }

  private static Optional<AttributeUse> httpAttribute(MethodDecl method) {
    return method.attributes().stream().filter(a -> HTTP_METHODS.containsKey(a.name())).findFirst();
  }

  /** The action's own template, from its Http attribute or a method-level [Route]. */
  static String actionTemplate(MethodDecl method, AttributeUse http) {
    String template =
        http.firstArgUnquoted()
            .or(() -> method.attributes().stream()
                .filter(a -> a.name().equals("Route"))
                .findFirst()
                .flatMap(AttributeUse::firstArgUnquoted))
            .orElse("");
    return normalise(template.replace("[action]", method.name()));
  }

  /** Strips slashes at either end and route constraints: {@code {id:int}} becomes {@code {id}}. */
  static String normalise(String route) {
    String r = route.replaceAll("\\{(\\w+)[^}]*}", "{$1}");
    r = r.replaceAll("^~?/+", "").replaceAll("/+$", "");
    return r;
  }

  static String joinRoutes(String base, String template) {
    if (template.isEmpty()) {
      return base.isEmpty() ? "/" : base;
    }
    return base + "/" + template;
  }

  private static Set<String> routeVariables(String route) {
    Set<String> vars = new LinkedHashSet<>();
    Matcher m = Pattern.compile("\\{(\\w+)}").matcher(route);
    while (m.find()) {
      vars.add(m.group(1));
    }
    return vars;
  }

  private static final Pattern FOREIGN_BODY =
      Pattern.compile(
          "\\b(?:BadRequest|NotFound|Conflict|UnprocessableEntity|Problem|ValidationProblem)\\s*\\(\\s*[^)\\s]"
              + "|\\bStatusCode\\s*\\([^,()]+,");

  /**
   * {@code ActionResult<T>} accepts any result, so {@code return BadRequest("reason");} is legal in
   * an action declared to return books. {@code ResponseEntity<List<Book>>} is not so forgiving: an
   * action that returns a body of another type needs {@code ResponseEntity<?>}.
   */
  private static TypeName widenedReturnType(MethodDecl method, TypeName mapped) {
    if (!returnsActionResultOfT(method.returnType())
        || method.bodyRaw() == null
        || !FOREIGN_BODY.matcher(method.bodyRaw()).find()) {
      return mapped;
    }
    return ParameterizedTypeName.get(
        ClassName.get("org.springframework.http", "ResponseEntity"),
        WildcardTypeName.subtypeOf(Object.class));
  }

  private static boolean returnsActionResultOfT(TypeRef returnType) {
    TypeRef t = returnType;
    if ((t.name().equals("Task") || t.name().equals("ValueTask")) && !t.typeArgs().isEmpty()) {
      t = t.typeArgs().get(0);
    }
    return t.name().equals("ActionResult") && !t.typeArgs().isEmpty();
  }

  // ------------------------------------------------------------- binding

  private List<AnnotationSpec> binding(
      ParamDecl param,
      Set<String> routeVariables,
      boolean apiController,
      TypeDecl type,
      MethodDecl method,
      SourceFile source,
      List<Finding> findings) {

    String name = param.name();
    String javaName = MethodEmitter.javaIdentifier(name);
    if (param.hasAttribute("FromRoute") || (!hasBindingAttribute(param) && routeVariables.contains(name))) {
      return List.of(named(PATH_VARIABLE, name, javaName, null));
    }
    if (param.hasAttribute("FromBody")) {
      return body(apiController);
    }
    if (param.hasAttribute("FromQuery")) {
      return List.of(requestParam(param, attributeName(param, "FromQuery").orElse(name), javaName));
    }
    if (param.hasAttribute("FromHeader")) {
      return List.of(named(REQUEST_HEADER, attributeName(param, "FromHeader").orElse(name), javaName, null));
    }
    if (param.hasAttribute("FromForm")) {
      return List.of(AnnotationSpec.builder(MODEL_ATTRIBUTE).build());
    }
    if (param.hasAttribute("FromServices")) {
      findings.add(
          Finding.at(
              FindingCode.UNSUPPORTED_CONSTRUCT,
              type.name() + "." + method.name() + " takes " + name + " via [FromServices]; move it"
                  + " to constructor injection.",
              source.path(),
              method.startLine()));
      return List.of();
    }
    if (isSimple(param.type())) {
      return List.of(requestParam(param, name, javaName));
    }
    return body(apiController);
  }

  private static boolean hasBindingAttribute(ParamDecl param) {
    return param.attributes().stream().anyMatch(a -> a.name().startsWith("From"));
  }

  private boolean isSimple(TypeRef type) {
    if (type.isArray() || type.isGeneric()) {
      return false;
    }
    return SIMPLE_TYPES.contains(type.name()) || typeMapper.map(type).type().primitive();
  }

  private static List<AnnotationSpec> body(boolean validate) {
    List<AnnotationSpec> annotations = new ArrayList<>();
    // [ApiController] validates the model automatically; @Valid is Spring's equivalent.
    if (validate) {
      annotations.add(AnnotationSpec.builder(VALID).build());
    }
    annotations.add(AnnotationSpec.builder(REQUEST_BODY).build());
    return annotations;
  }

  private static AnnotationSpec requestParam(ParamDecl param, String name, String javaName) {
    AnnotationSpec.Builder spec = AnnotationSpec.builder(REQUEST_PARAM);
    if (!name.equals(javaName)) {
      spec.addMember("name", "$S", name);
    }
    if (param.defaultValue() != null && !param.defaultValue().equals("null")) {
      String value = param.defaultValue().replaceAll("^\"|\"$", "");
      spec.addMember("defaultValue", "$S", value);
    } else if (param.type().nullable() || "null".equals(param.defaultValue())) {
      spec.addMember("required", "false");
    }
    return spec.build();
  }

  private static AnnotationSpec named(ClassName annotation, String name, String javaName, String unused) {
    AnnotationSpec.Builder spec = AnnotationSpec.builder(annotation);
    if (!name.equals(javaName)) {
      spec.addMember("value", "$S", name);
    }
    return spec.build();
  }

  private static Optional<String> attributeName(ParamDecl param, String attribute) {
    return param.attributes().stream()
        .filter(a -> a.name().equals(attribute))
        .findFirst()
        .map(a -> a.namedArgs().get("Name"))
        .map(n -> n.replaceAll("^\"|\"$", ""));
  }

  /** Validation attributes on parameters, such as {@code [Range(1, 100)] int pageSize}. */
  private List<AnnotationSpec> constraintAnnotations(ParamDecl param) {
    List<JavaAnnotation> mapped =
        attributeMapper.mapAll(param.attributes()).annotations().stream()
            .filter(a -> a.type().packageName().equals("jakarta.validation.constraints"))
            .toList();
    return JavaPoetTypes.toAnnotationSpecs(attributeMapper.merge(mapped));
  }

  // ------------------------------------------------------------ security

  /**
   * {@code [Authorize]} becomes {@code @PreAuthorize}. Spring Security configuration has no
   * counterpart in the source to translate, so every use raises a finding.
   */
  private Optional<AnnotationSpec> authorization(
      List<AttributeUse> attributes, String where, SourceFile source, int line, List<Finding> findings) {
    for (AttributeUse attribute : attributes) {
      if (attribute.name().equals("AllowAnonymous")) {
        findings.add(
            Finding.at(
                FindingCode.AUTH_ATTRIBUTE,
                where + " is [AllowAnonymous]. Spring has no per-method equivalent: permit the"
                    + " path in the security filter chain.",
                source.path(),
                line));
      }
      if (!attribute.name().equals("Authorize")) {
        continue;
      }
      String roles = attribute.namedArgs().get("Roles");
      String policy = attribute.namedArgs().get("Policy");
      String expression;
      if (roles != null) {
        List<String> names = List.of(roles.replaceAll("^\"|\"$", "").split("\\s*,\\s*"));
        expression = names.size() == 1
            ? "hasRole('" + names.get(0) + "')"
            : "hasAnyRole(" + String.join(", ", names.stream().map(n -> "'" + n + "'").toList()) + ")";
      } else {
        expression = "isAuthenticated()";
      }
      findings.add(
          Finding.at(
              FindingCode.AUTH_ATTRIBUTE,
              where + " required authorization"
                  + (policy == null ? "" : " (policy " + policy + ", not translated)")
                  + ". Configure Spring Security to match the ASP.NET authentication scheme.",
              source.path(),
              line));
      return Optional.of(AnnotationSpec.builder(PRE_AUTHORIZE).addMember("value", "$S", expression).build());
    }
    return Optional.empty();
  }

  static boolean isController(TypeDecl type) {
    return type.kind() == TypeKind.CLASS;
  }
}
