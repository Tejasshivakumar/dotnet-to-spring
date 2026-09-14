package com.portway.core.generate;

import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;
import com.portway.core.ir.FieldDecl;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.TypeDecl;
import java.util.List;
import javax.lang.model.element.Modifier;

/**
 * Emits C# enums as Java enums.
 *
 * <p>Easy to overlook and not optional: an entity with a {@code Genre} property does not compile
 * unless {@code Genre} exists. Enums carry no role in the classifier because they are not a
 * migration concern in themselves, so generation is driven by their kind instead.
 *
 * <p>Explicit numeric values are dropped. C# enums are integers with names; Java enums are objects
 * with an ordinal. Persisting them as strings, which is what the entity generator emits, makes the
 * numeric value irrelevant and the stored data readable.
 */
public class EnumGenerator {

  private final JavaFormatter formatter;

  public EnumGenerator(JavaFormatter formatter) {
    this.formatter = formatter;
  }

  public GeneratedFile generate(TypeDecl enumType, SourceFile source, String packageName) {
    TypeSpec.Builder builder =
        TypeSpec.enumBuilder(enumType.name()).addModifiers(Modifier.PUBLIC);

    if (enumType.docComment() != null) {
      builder.addJavadoc("$L\n", Javadoc.fromXmlDoc(enumType.docComment()));
    }

    for (FieldDecl constant : enumType.fields()) {
      builder.addEnumConstant(constant.name());
    }

    JavaFile javaFile =
        JavaFile.builder(packageName, builder.build())
            .skipJavaLangImports(true)
            .indent("  ")
            .build();

    String path =
        "src/main/java/" + packageName.replace('.', '/') + "/" + enumType.name() + ".java";
    return new GeneratedFile(
        path, formatter.format(javaFile.toString()), source.path(), List.of());
  }
}
