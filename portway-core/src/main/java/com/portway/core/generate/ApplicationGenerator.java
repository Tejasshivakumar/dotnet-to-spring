package com.portway.core.generate;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Modifier;

/**
 * The Spring Boot entry point, and the small configuration classes the generated code needs.
 *
 * <p>Program.cs is not translated line by line. Its service registrations are already expressed by
 * {@code @Service} and constructor injection, its DbContext registration by Spring Data, and its
 * middleware pipeline by Spring Boot's defaults. What remains is a main method.
 */
public class ApplicationGenerator {

  public static final String APPLICATION_CLASS = "MigratedApplication";

  private final JavaFormatter formatter;

  public ApplicationGenerator(JavaFormatter formatter) {
    this.formatter = formatter;
  }

  public GeneratedFile application(String basePackage, boolean configurationProperties) {
    TypeSpec.Builder app =
        TypeSpec.classBuilder(APPLICATION_CLASS)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(
                ClassName.get("org.springframework.boot.autoconfigure", "SpringBootApplication"));
    if (configurationProperties) {
      app.addAnnotation(
          ClassName.get("org.springframework.boot.context.properties", "ConfigurationPropertiesScan"));
    }
    app.addMethod(
        MethodSpec.methodBuilder("main")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(String[].class, "args")
            .addStatement(
                "$T.run($L.class, args)",
                ClassName.get("org.springframework.boot", "SpringApplication"),
                APPLICATION_CLASS)
            .build());
    return write(basePackage, app.build(), "Program.cs");
  }

  /**
   * {@code @PreAuthorize} does nothing unless method security is switched on. The filter chain
   * itself is left at Spring's defaults, which the AUTH_ATTRIBUTE findings tell the reviewer to
   * configure.
   */
  public GeneratedFile securityConfig(String configPackage) {
    TypeSpec config =
        TypeSpec.classBuilder("SecurityConfig")
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc(
                "Enables {@code @PreAuthorize} on the migrated controllers.\n\n"
                    + "<p>MIGRATION: authentication itself was not translated. Configure a\n"
                    + "{@code SecurityFilterChain} to match the ASP.NET authentication scheme.\n")
            .addAnnotation(ClassName.get("org.springframework.context.annotation", "Configuration"))
            .addAnnotation(
                ClassName.get(
                    "org.springframework.security.config.annotation.method.configuration",
                    "EnableMethodSecurity"))
            .build();
    return write(configPackage, config, null);
  }

  private GeneratedFile write(String packageName, TypeSpec type, String sourcePath) {
    JavaFile file = JavaFile.builder(packageName, type).skipJavaLangImports(true).indent("  ").build();
    return new GeneratedFile(
        GenerationContext.javaPath(packageName, type.name()),
        formatter.format(file.toString()),
        sourcePath,
        List.of());
  }

  static List<AnnotationSpec> none() {
    return new ArrayList<>();
  }
}
