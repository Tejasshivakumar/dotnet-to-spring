package com.portway.core.generate;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.TypeName;
import com.portway.core.ir.MethodDecl;
import com.portway.core.ir.ParamDecl;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.TypeDecl;
import com.portway.core.ir.TypeRef;
import com.portway.core.report.Finding;
import com.portway.core.report.FindingCode;
import com.portway.core.report.MethodReport;
import com.portway.core.report.Severity;
import com.portway.core.report.Strategy;
import com.portway.core.rules.JavaType;
import com.portway.core.rules.MappedType;
import com.portway.core.rules.TypeMapper;
import com.portway.core.rules.body.BodyRewriter;
import com.portway.core.rules.body.JavaImports;
import com.portway.core.rules.body.RewriteContext;
import com.portway.core.rules.body.RewriteResult;
import com.portway.core.rules.body.Tier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;

/**
 * Emits one method: its signature from the IR, and its body from whichever source wins.
 *
 * <p>The order of precedence is the heart of the migration's honesty. A method a human or the
 * verifier demoted is stubbed, whatever else is available. Otherwise an externally supplied body
 * (the LLM's) is used if there is one. Otherwise the rule engine's rewrite is used if it reached
 * tier A. Anything left becomes a stub that compiles, throws, and carries the original C# in its
 * Javadoc, so the project builds and the reviewer has an exact, actionable item.
 */
public class MethodEmitter {

  private final TypeMapper typeMapper;
  private final BodyRewriter rewriter = new BodyRewriter();

  public MethodEmitter(TypeMapper typeMapper) {
    this.typeMapper = typeMapper;
  }

  /**
   * The parts of a signature. Generators start from {@link #defaultShape} and adjust: controllers
   * add mapping annotations and parameter binding, for example.
   */
  public record Shape(
      String javaName,
      TypeName returnType,
      List<ParameterSpec> params,
      boolean varargs,
      List<AnnotationSpec> annotations,
      List<Modifier> modifiers,
      List<FindingCode> notes) {}

  /** What emitting one method produced. */
  public record Emitted(
      MethodSpec spec, MethodReport report, List<Finding> findings, Set<String> repositories) {}

  /** Stable identity for a method across regenerations: {@code Type.Name(ParamType,...)}. */
  public static String methodKey(TypeDecl owner, MethodDecl method) {
    return owner.name()
        + "."
        + method.name()
        + method.params().stream()
            .map(p -> typeText(p.type()))
            .collect(Collectors.joining(",", "(", ")"));
  }

  static String typeText(TypeRef type) {
    StringBuilder sb = new StringBuilder(type.name());
    if (type.isGeneric()) {
      sb.append(
          type.typeArgs().stream().map(MethodEmitter::typeText).collect(Collectors.joining(",", "<", ">")));
    }
    if (type.nullable()) {
      sb.append('?');
    }
    sb.append("[]".repeat(type.arrayRank()));
    return sb.toString();
  }

  /** The signature as the IR describes it, with every type mapped and placed. */
  public Shape defaultShape(MethodDecl method, GenerationContext context, boolean inInterface) {
    List<FindingCode> notes = new ArrayList<>();
    MappedType returnType = typeMapper.map(method.returnType());
    keepSignatureNotes(returnType, notes);

    List<ParameterSpec> params = new ArrayList<>();
    boolean varargs = false;
    for (ParamDecl param : method.params()) {
      MappedType mapped = typeMapper.map(param.type());
      keepSignatureNotes(mapped, notes);
      TypeName type = JavaPoetTypes.toTypeName(context.qualify(mapped.type()), context.modelPackage());
      if (param.isParamsArray()) {
        varargs = true;
        if (!(type instanceof ArrayTypeName)) {
          type = ArrayTypeName.of(type);
        }
      }
      params.add(ParameterSpec.builder(type, javaIdentifier(param.name())).build());
    }

    List<Modifier> modifiers = new ArrayList<>();
    if (inInterface) {
      modifiers.add(Modifier.PUBLIC);
      modifiers.add(Modifier.ABSTRACT);
    } else {
      for (String m : method.modifiers()) {
        switch (m) {
          case "public" -> modifiers.add(Modifier.PUBLIC);
          case "protected" -> modifiers.add(Modifier.PROTECTED);
          case "private" -> modifiers.add(Modifier.PRIVATE);
          case "static" -> modifiers.add(Modifier.STATIC);
          default -> {
            // async, virtual, override, sealed, internal: no Java counterpart on a method
          }
        }
      }
    }

    List<AnnotationSpec> annotations = new ArrayList<>();
    if (method.hasAttribute("Obsolete")) {
      annotations.add(AnnotationSpec.builder(Deprecated.class).build());
    }

    return new Shape(
        Names.methodName(method.name()),
        JavaPoetTypes.toTypeName(context.qualify(returnType.type()), context.modelPackage()),
        params,
        varargs,
        annotations,
        modifiers,
        notes);
  }

  /**
   * Nullable reference notes on signatures are dropped: every {@code Task<Book?>} would raise one,
   * and property-level notes already cover the model. Async and numeric notes stay.
   */
  private static void keepSignatureNotes(MappedType mapped, List<FindingCode> notes) {
    for (FindingCode code : mapped.notes()) {
      if (code != FindingCode.NULLABLE_REFERENCE && !notes.contains(code)) {
        notes.add(code);
      }
    }
  }

  /**
   * Emits the method.
   *
   * @param rewriteContext the class-level context; return-type flags are set here
   */
  public Emitted emit(
      TypeDecl owner,
      MethodDecl method,
      SourceFile source,
      Shape shape,
      RewriteContext rewriteContext,
      MigrationOptions options,
      String generatedPath,
      String javaClassName,
      List<String> javaFields,
      boolean abstractMethod) {

    List<Finding> findings = new ArrayList<>();
    String key = methodKey(owner, method);
    String where = owner.name() + "." + method.name();
    // An interface declaration changes no behaviour; the async finding belongs on the
    // implementation and the controller, where the synchronous code actually runs.
    for (FindingCode code : shape.notes()) {
      if (!(abstractMethod && code == FindingCode.ASYNC_DROPPED)) {
        findings.add(finding(code, signatureMessage(code, where), source, method));
      }
    }

    MethodSpec.Builder builder =
        MethodSpec.methodBuilder(shape.javaName())
            .addModifiers(shape.modifiers())
            .returns(shape.returnType())
            .addParameters(shape.params())
            .varargs(shape.varargs())
            .addAnnotations(shape.annotations());

    String doc = Javadoc.fromXmlDoc(method.docComment());
    String csharpSource = csharpSource(method);
    Set<String> repositories = new LinkedHashSet<>();

    Tier tier = null;
    Strategy strategy = Strategy.RULE_MAPPED;
    String reason = null;

    if (abstractMethod || !method.hasBody()) {
      if (doc != null) {
        builder.addJavadoc("$L\n", doc);
      }
    } else {
      boolean returnsVoid = shape.returnType().equals(TypeName.VOID);
      RewriteResult rewrite =
          rewriter.rewrite(
              method.bodyRaw(),
              rewriteContext.withReturnsVoid(returnsVoid),
              method.params().stream().anyMatch(ParamDecl::isByReference));
      tier = rewrite.tier();
      for (FindingCode code : rewrite.notes()) {
        // An unsupported construct is reported once, by the METHOD_STUBBED finding below.
        boolean duplicate =
            (code == FindingCode.ASYNC_DROPPED && shape.notes().contains(code))
                || code == FindingCode.UNSUPPORTED_CONSTRUCT;
        if (!duplicate) {
          findings.add(finding(code, bodyMessage(code, where, rewrite.reason()), source, method));
        }
      }

      String forced = options.forcedManual().get(key);
      MigrationOptions.BodyOverride override = options.bodyOverrides().get(key);

      if (forced != null) {
        strategy = Strategy.MANUAL_REQUIRED;
        reason = forced;
      } else if (override != null) {
        strategy = override.strategy();
        String marked = JavaImports.markSimpleNames(override.javaBody(), override.imports());
        if (doc != null) {
          builder.addJavadoc("$L\n", doc);
        }
        builder.addCode(JavaCode.fromMarkedText(marked));
      } else if (rewrite.succeeded()) {
        if (doc != null) {
          builder.addJavadoc("$L\n", doc);
        }
        builder.addCode(JavaCode.fromMarkedText(rewrite.javaBody()));
        repositories.addAll(rewrite.repositories());
      } else {
        strategy = Strategy.MANUAL_REQUIRED;
        reason = rewrite.reason();
      }

      if (strategy == Strategy.MANUAL_REQUIRED) {
        stub(builder, doc, reason, csharpSource);
        findings.add(
            new Finding(
                Severity.HIGH,
                FindingCode.METHOD_STUBBED,
                where + " was not migrated: " + reason + ".",
                source.path(),
                method.startLine(),
                generatedPath,
                null));
      }
    }

    MethodSpec spec = builder.build();
    MethodReport report =
        new MethodReport(
            key,
            owner.name(),
            method.name(),
            source.path(),
            method.startLine(),
            method.endLine(),
            generatedPath,
            tier,
            strategy,
            reason,
            javaClassName,
            signature(shape),
            javaFields,
            csharpSource);
    return new Emitted(spec, report, findings, repositories);
  }

  private static void stub(MethodSpec.Builder builder, String doc, String reason, String csharp) {
    StringBuilder javadoc = new StringBuilder();
    if (doc != null) {
      javadoc.append(doc).append("\n\n");
    }
    javadoc
        .append("MIGRATION: could not be translated automatically.\n")
        .append("Reason: ")
        .append(JavaCode.javadocEscape(reason))
        .append(".\n\nOriginal C#:\n<pre>\n")
        .append(JavaCode.javadocEscape(csharp))
        .append("\n</pre>\n");
    builder.addJavadoc("$L", javadoc.toString());
    builder.addStatement(
        "throw new $T($S)", ClassName.get(UnsupportedOperationException.class), "TODO: migrate from C#");
  }

  /** The original method as the reviewer knows it: signature line plus body, re-indented. */
  static String csharpSource(MethodDecl method) {
    String params =
        method.params().stream()
            .map(p -> (p.modifier() == null ? "" : p.modifier() + " ") + typeText(p.type()) + " " + p.name())
            .collect(Collectors.joining(", "));
    String signature =
        String.join(" ", method.modifiers())
            + (method.modifiers().isEmpty() ? "" : " ")
            + typeText(method.returnType())
            + " "
            + method.name()
            + "("
            + params
            + ")";
    if (!method.hasBody()) {
      return signature + ";";
    }
    if (method.isExpressionBodied()) {
      return signature + " " + method.bodyRaw() + ";";
    }
    return signature + "\n" + dedentBody(method.bodyRaw());
  }

  /** The body's continuation lines carry the file's indentation; the first line does not. */
  private static String dedentBody(String body) {
    String[] lines = body.split("\n", -1);
    int indent = Integer.MAX_VALUE;
    for (int i = 1; i < lines.length; i++) {
      if (!lines[i].isBlank()) {
        indent = Math.min(indent, lines[i].length() - lines[i].stripLeading().length());
      }
    }
    // The closing brace sits at the declaration's indentation, which is the common minimum.
    StringBuilder out = new StringBuilder(lines[0]);
    for (int i = 1; i < lines.length; i++) {
      out.append('\n').append(lines[i].isBlank() ? "" : lines[i].substring(Math.min(indent, lines[i].length())));
    }
    return out.toString();
  }

  static String signature(Shape shape) {
    String modifiers =
        shape.modifiers().stream()
            .filter(m -> m != Modifier.ABSTRACT)
            .map(Modifier::toString)
            .collect(Collectors.joining(" "));
    List<String> params = new ArrayList<>();
    for (int i = 0; i < shape.params().size(); i++) {
      ParameterSpec p = shape.params().get(i);
      String type = JavaCode.simpleNames(p.type());
      if (shape.varargs() && i == shape.params().size() - 1 && type.endsWith("[]")) {
        type = type.substring(0, type.length() - 2) + "...";
      }
      params.add(type + " " + p.name());
    }
    return (modifiers.isEmpty() ? "" : modifiers + " ")
        + JavaCode.simpleNames(shape.returnType())
        + " "
        + shape.javaName()
        + "("
        + String.join(", ", params)
        + ")";
  }

  /** C# parameter names are camelCase already, but may be Java keywords such as {@code default}. */
  static String javaIdentifier(String name) {
    String cleaned = name.startsWith("@") ? name.substring(1) : name;
    return SourceVersion.isKeyword(cleaned) ? cleaned + "Value" : cleaned;
  }

  private static Finding finding(FindingCode code, String message, SourceFile source, MethodDecl method) {
    return Finding.at(code, message, source.path(), method.startLine());
  }

  private static String signatureMessage(FindingCode code, String where) {
    return switch (code) {
      case ASYNC_DROPPED ->
          where + " was asynchronous; the generated method is synchronous. Consider @Async or a"
              + " reactive type if the caller relied on concurrency.";
      case DECIMAL_ARITHMETIC -> where + " takes or returns decimal, mapped to BigDecimal.";
      case UNSIGNED_BYTE -> where + " uses an unsigned C# type; the Java type is signed.";
      default -> where + ": " + code.name();
    };
  }

  private static String bodyMessage(FindingCode code, String where, String reason) {
    return switch (code) {
      case ASYNC_DROPPED -> where + " awaited asynchronous calls; they are now synchronous.";
      case DECIMAL_ARITHMETIC ->
          where + " does arithmetic on decimal values. BigDecimal has no operators; the body"
              + " needs add/multiply calls.";
      case NULLABLE_REFERENCE ->
          where + " uses null-conditional access, rewritten as explicit null checks.";
      case EF_FLUENT_CONFIG -> where + " " + reason + ".";
      case UNSUPPORTED_CONSTRUCT -> where + " " + (reason == null ? "uses an unsupported construct" : reason) + ".";
      default -> where + ": " + code.name() + (reason == null ? "" : " (" + reason + ")");
    };
  }

  /** The Java type of a mapped C# type, placed in its generated package. */
  public TypeName javaType(TypeRef type, GenerationContext context, boolean forceBoxed) {
    JavaType mapped = typeMapper.map(type, forceBoxed).type();
    return JavaPoetTypes.toTypeName(context.qualify(mapped), context.modelPackage());
  }
}
