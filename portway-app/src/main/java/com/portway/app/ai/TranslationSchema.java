package com.portway.app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.portway.core.translate.Translation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The contract every model response must meet, checked in order, with no salvaging:
 *
 * <ol>
 *   <li>It parses as JSON. If not, it is rejected; nothing is regex-scraped out of prose.
 *   <li>It validates against {@code translation-response.schema.json}.
 *   <li>The body is a body: no markdown fences, no method signature, no class or package
 *       declaration.
 * </ol>
 *
 * <p>Structured outputs already constrain the response to this schema. It is checked again here
 * because the compile verifier is the only thing that decides what is kept, and it should only
 * ever see well-formed candidates.
 */
public class TranslationSchema {

  /** The response broke the contract. The message says how, for the log and the repair loop. */
  public static class InvalidResponse extends RuntimeException {
    public InvalidResponse(String message) {
      super(message);
    }
  }

  private static final String RESOURCE = "/ai/translation-response.schema.json";
  private static final Set<String> UNSUPPORTED_BY_API =
      Set.of("minLength", "maxLength", "pattern", "minimum", "maximum", "$schema", "title", "description");
  private static final Pattern SIGNATURE =
      Pattern.compile("^(public|private|protected|static)\\b");
  private static final Pattern DECLARATION =
      Pattern.compile("(?m)^\\s*(package\\s+[\\w.]+\\s*;|import\\s+[\\w.*]+\\s*;|(public\\s+|final\\s+|abstract\\s+)*(class|interface|record|enum)\\s+\\w+\\s*[{<(])");

  private final ObjectMapper json = new ObjectMapper();
  private final JsonSchema schema;
  private final Map<String, Object> apiSchema;

  public TranslationSchema() {
    try (InputStream in = TranslationSchema.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing " + RESOURCE);
      }
      JsonNode node = json.readTree(in);
      this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node);
      @SuppressWarnings("unchecked")
      Map<String, Object> raw = json.convertValue(node, Map.class);
      this.apiSchema = strip(raw);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The schema as sent to the API, without the validation keywords structured outputs does not
   * accept. The full schema is still enforced locally.
   */
  public Map<String, Object> apiSchema() {
    return apiSchema;
  }

  public Translation parse(String text) {
    if (text == null || text.isBlank()) {
      throw new InvalidResponse("empty response");
    }
    JsonNode node;
    try {
      node = json.readTree(text);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new InvalidResponse("not valid JSON: " + e.getOriginalMessage());
    }
    Set<ValidationMessage> errors = schema.validate(node);
    if (!errors.isEmpty()) {
      throw new InvalidResponse(
          "does not match the schema: "
              + errors.stream().map(ValidationMessage::toString).sorted().collect(Collectors.joining("; ")));
    }
    String body = node.get("javaBody").asText();
    String trimmed = body.strip();
    if (trimmed.contains("```")) {
      throw new InvalidResponse("the body contains markdown fences");
    }
    if (SIGNATURE.matcher(trimmed).find()) {
      throw new InvalidResponse("the body starts with a modifier, so it is a signature, not a body");
    }
    if (DECLARATION.matcher(trimmed).find()) {
      throw new InvalidResponse("the body declares a class, package or import");
    }
    List<String> imports = new ArrayList<>();
    node.get("requiredImports").forEach(i -> imports.add(i.asText()));
    return new Translation(body, imports, node.get("notes").asText(), node.get("confidence").asDouble());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> strip(Map<String, Object> node) {
    Map<String, Object> out = new LinkedHashMap<>();
    node.forEach((key, value) -> {
      if (UNSUPPORTED_BY_API.contains(key)) {
        return;
      }
      if (value instanceof Map<?, ?> map) {
        out.put(key, strip((Map<String, Object>) map));
      } else {
        out.put(key, value);
      }
    });
    return out;
  }
}
