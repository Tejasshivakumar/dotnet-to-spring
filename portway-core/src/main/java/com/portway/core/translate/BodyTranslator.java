package com.portway.core.translate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Translates method bodies the rule engine could not handle. In practice, an LLM.
 *
 * <p>Defined in core so the verification loop can use one without knowing what it is; the
 * implementation lives in the application, next to its HTTP client, cache and budget. Whatever it
 * returns is treated as untrusted input: it is spliced in, compiled, and demoted if it fails.
 */
public interface BodyTranslator {

  /** A first translation, or empty when the translator declines or fails. */
  Optional<Translation> translate(TranslationRequest request);

  /**
   * A second attempt, given the previous body and what the compiler said about it. There is only
   * ever one repair round: a translator that fails twice is not converging.
   */
  Optional<Translation> repair(TranslationRequest request, String previousBody, String compilerErrors);

  /**
   * Translates several methods. The default is sequential; implementations may parallelise within
   * their own rate limits.
   */
  default Map<String, Translation> translateAll(List<TranslationRequest> requests) {
    Map<String, Translation> results = new LinkedHashMap<>();
    for (TranslationRequest request : requests) {
      translate(request).ifPresent(t -> results.put(request.methodKey(), t));
    }
    return results;
  }
}
