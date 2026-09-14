package com.portway.core.generate;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.WildcardTypeName;
import com.portway.core.rules.JavaAnnotation;
import com.portway.core.rules.JavaType;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridges the rules layer's plain-data types onto JavaPoet.
 *
 * <p>Kept separate so {@link com.portway.core.rules.TypeMapper} and {@link
 * com.portway.core.rules.AttributeMapper} stay testable as pure data transforms, with no code
 * generator anywhere near them.
 *
 * <p>Note for anyone porting code from Square's JavaPoet: the Palantir fork dropped the
 * {@code TypeName.OBJECT} constant, so Object is spelled {@code ClassName.get(Object.class)}.
 */
public final class JavaPoetTypes {

  private JavaPoetTypes() {}

  /** Converts a resolved Java type into a JavaPoet TypeName. */
  public static TypeName toTypeName(JavaType type, String defaultPackage) {
    TypeName base = baseTypeName(type, defaultPackage);

    if (type.wildcard() && base instanceof ClassName className) {
      base =
          ParameterizedTypeName.get(
              className, WildcardTypeName.subtypeOf(ClassName.get(Object.class)));
    } else if (!type.typeArgs().isEmpty() && base instanceof ClassName className) {
      TypeName[] args =
          type.typeArgs().stream()
              .map(arg -> toTypeName(arg, defaultPackage))
              .toArray(TypeName[]::new);
      base = ParameterizedTypeName.get(className, args);
    }

    for (int i = 0; i < type.arrayDimensions(); i++) {
      base = com.palantir.javapoet.ArrayTypeName.of(base);
    }
    return base;
  }

  private static TypeName baseTypeName(JavaType type, String defaultPackage) {
    if (type.primitive()) {
      return switch (type.simpleName()) {
        case "int" -> TypeName.INT;
        case "long" -> TypeName.LONG;
        case "short" -> TypeName.SHORT;
        case "byte" -> TypeName.BYTE;
        case "boolean" -> TypeName.BOOLEAN;
        case "char" -> TypeName.CHAR;
        case "double" -> TypeName.DOUBLE;
        case "float" -> TypeName.FLOAT;
        case "void" -> TypeName.VOID;
        default -> ClassName.get(Object.class);
      };
    }
    // A type with no package is one of the project's own, so it lands in whatever
    // package the generator put it in.
    String packageName = type.packageName().isEmpty() ? defaultPackage : type.packageName();
    return ClassName.get(packageName, type.simpleName());
  }

  /** Converts a mapped annotation into a JavaPoet AnnotationSpec. */
  public static AnnotationSpec toAnnotationSpec(JavaAnnotation annotation) {
    ClassName type =
        ClassName.get(annotation.type().packageName(), annotation.type().simpleName());
    AnnotationSpec.Builder builder = AnnotationSpec.builder(type);

    for (Map.Entry<String, String> member : annotation.members().entrySet()) {
      builder.addMember(member.getKey(), literal(member.getValue()));
    }
    return builder.build();
  }

  /**
   * Renders an annotation member value.
   *
   * <p>A fully qualified enum constant such as {@code jakarta.persistence.GenerationType.IDENTITY}
   * is emitted through {@code $T} so JavaPoet registers the import and writes
   * {@code GenerationType.IDENTITY}. Emitting it as a plain literal would leave the qualified name
   * inline, which compiles but reads like machine output.
   */
  private static CodeBlock literal(String value) {
    Matcher qualifiedConstant = QUALIFIED_CONSTANT.matcher(value);
    if (qualifiedConstant.matches()) {
      ClassName owner =
          ClassName.get(qualifiedConstant.group(1), qualifiedConstant.group(2));
      return CodeBlock.of("$T.$L", owner, qualifiedConstant.group(3));
    }
    return CodeBlock.of("$L", value);
  }

  /** {@code a.b.C.MEMBER}: a lowercase package, a capitalised type, then a constant. */
  private static final Pattern QUALIFIED_CONSTANT =
      Pattern.compile("((?:[a-z][A-Za-z0-9_]*\\.)+[a-z][A-Za-z0-9_]*)\\.([A-Z][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)");

  public static List<AnnotationSpec> toAnnotationSpecs(List<JavaAnnotation> annotations) {
    return annotations.stream().map(JavaPoetTypes::toAnnotationSpec).toList();
  }
}
