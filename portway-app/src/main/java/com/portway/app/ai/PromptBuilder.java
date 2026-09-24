package com.portway.app.ai;

import com.portway.core.rules.TypeMapper;
import com.portway.core.translate.TranslationRequest;

/**
 * Builds the two halves of every prompt.
 *
 * <p>The system prompt is the same for every call, which is what lets it be cached. It carries the
 * type-mapping table rendered from the rule engine's own {@code type-mappings.yaml}, so the model
 * and the rules can never disagree about what {@code decimal} becomes. The user message carries
 * one method: the Java signature it must fill, the class's fields, the imports already present,
 * and the original C#.
 */
public class PromptBuilder {

  private final String system;

  public PromptBuilder(TypeMapper typeMapper) {
    this.system =
        """
        You translate the body of one C# method into Java 21 for a Spring Boot 3 application that \
        was generated from an ASP.NET Core project.

        The surrounding class, its fields and the method signature have already been generated. \
        Write only the statements that go inside the method's braces.

        Rules:
        - Output the body only: no signature, no enclosing braces, no class, no package or import \
        lines, no markdown fences.
        - Use only the fields listed and the method parameters. Do not invent dependencies.
        - C# properties are Java getters and setters on the generated classes: book.Title becomes \
        book.getTitle(); a primitive boolean property InStock becomes isInStock().
        - decimal is java.math.BigDecimal: use add, subtract, multiply and compareTo, never \
        arithmetic operators.
        - Async code is now synchronous: drop await and call the synchronous method.
        - EF Core DbSets have become Spring Data repositories (for example bookRepository.findAll(), \
        findById(id), save(entity), delete(entity)); SaveChanges is not needed, the service is \
        @Transactional.
        - LINQ becomes one Java stream pipeline per chain.
        - List every type you use by simple name that is not already imported in requiredImports, \
        fully qualified.
        - If the method cannot be translated faithfully, still return your best translation and \
        explain what the reviewer must check in notes, with a lower confidence.

        C# to Java type mappings used by the generated code:
        """
            + typeMapper.asPromptTable();
  }

  public String system() {
    return system;
  }

  public String user(TranslationRequest request) {
    StringBuilder sb = new StringBuilder();
    sb.append("Class: ").append(request.javaClassName()).append("\n\n");
    sb.append("Fields:\n");
    if (request.javaFields().isEmpty()) {
      sb.append("  (none)\n");
    }
    request.javaFields().forEach(f -> sb.append("  ").append(f).append(";\n"));
    sb.append("\nImports already in the file:\n");
    request.availableImports().forEach(i -> sb.append("  ").append(i).append('\n'));
    sb.append("\nJava signature to fill:\n  ").append(request.javaSignature()).append("\n\n");
    if (request.reason() != null) {
      sb.append("Why the rule engine could not translate it: ").append(request.reason()).append("\n\n");
    }
    sb.append("Original C#:\n").append(request.csharpSource()).append('\n');
    return sb.toString();
  }

  /** A repair request: the same method, the previous attempt, and what the compiler said. */
  public String repair(TranslationRequest request, String previousBody, String compilerErrors) {
    return user(request)
        + "\nYour previous translation of this body did not compile.\n\nPrevious body:\n"
        + previousBody
        + "\n\nCompiler errors (line numbers refer to the whole generated file):\n"
        + compilerErrors
        + "\n\nReturn a corrected body.\n";
  }
}
