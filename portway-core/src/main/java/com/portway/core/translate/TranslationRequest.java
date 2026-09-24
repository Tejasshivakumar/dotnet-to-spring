package com.portway.core.translate;

import com.portway.core.report.MethodReport;
import java.util.List;

/**
 * Everything a translator needs for one method, and nothing it does not.
 *
 * <p>The model sees the target, not the project: the class it is writing into, the fields it may
 * use, the exact signature it must fill, the imports already present, and the original C#. It
 * never writes the signature itself, so it cannot change the method's contract.
 *
 * @param methodKey identity of the method across regenerations
 * @param reason why the rule engine gave up, which tells the model what to focus on
 */
public record TranslationRequest(
    String methodKey,
    String javaClassName,
    List<String> javaFields,
    String javaSignature,
    List<String> availableImports,
    String csharpSource,
    String reason) {

  public TranslationRequest {
    javaFields = List.copyOf(javaFields == null ? List.of() : javaFields);
    availableImports = List.copyOf(availableImports == null ? List.of() : availableImports);
  }

  public static TranslationRequest of(MethodReport method, List<String> imports) {
    return new TranslationRequest(
        method.key(),
        method.javaClassName(),
        method.javaFields(),
        method.javaSignature(),
        imports,
        method.csharpSource(),
        method.reason());
  }
}
