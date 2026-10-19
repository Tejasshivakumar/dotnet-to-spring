package com.portway.app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Any endpoint that speaks the OpenAI Chat Completions API: Google Gemini, Groq, OpenRouter and a
 * local Ollama all do, and all have a free tier or are free outright.
 *
 * <ul>
 *   <li>The response is constrained to the translation JSON schema with {@code response_format}
 *       where the provider supports it, or to JSON mode where it does not. The schema is also
 *       spelled out in the system prompt, because JSON mode constrains syntax, not shape. Either
 *       way the response is validated locally: the model is checked, never trusted.
 *   <li>429s and 5xx responses are retried with exponential backoff, honouring {@code
 *       Retry-After}, up to {@code maxRetries} times. Free tiers rate-limit often.
 *   <li>A {@code content_filter} finish or a {@code refusal} is reported as a refusal; a {@code
 *       length} finish as truncated output.
 * </ul>
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

  /** Base URLs for the providers that need nothing but a key and a model name. */
  static final Map<String, String> BASE_URLS =
      Map.of(
          "gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
          "groq", "https://api.groq.com/openai/v1",
          "openrouter", "https://openrouter.ai/api/v1",
          "ollama", "http://localhost:11434/v1");

  static final String SCHEMA_INSTRUCTIONS =
      """


      Respond with one JSON object and nothing else, with exactly these keys:
      - "javaBody" (string): the Java statements of the method body
      - "requiredImports" (array of strings): fully qualified names of types the body needs imported
      - "notes" (string): what the reviewer should check
      - "confidence" (number from 0 to 1)
      """;

  private static final long MAX_BACKOFF_MS = 60_000;

  private final AiProperties properties;
  private final Map<String, Object> schema;
  private final URI endpoint;
  private final String apiKey;
  private final HttpClient http;
  private final ObjectMapper json = new ObjectMapper();

  public OpenAiCompatibleLlmClient(AiProperties properties, Map<String, Object> apiSchema) {
    this(properties, apiSchema, System.getenv("PORTWAY_AI_API_KEY"));
  }

  OpenAiCompatibleLlmClient(AiProperties properties, Map<String, Object> apiSchema, String apiKey) {
    this.properties = properties;
    this.schema = apiSchema;
    this.endpoint = URI.create(baseUrl(properties) + "/chat/completions");
    this.apiKey = apiKey;
    if ((apiKey == null || apiKey.isBlank()) && !provider(properties).equals("ollama")) {
      throw new IllegalStateException(
          "PORTWAY_AI_API_KEY is not set; provider " + properties.provider() + " needs an API key");
    }
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
  }

  static String baseUrl(AiProperties properties) {
    String url = properties.baseUrl();
    if (url == null || url.isBlank()) {
      url = BASE_URLS.get(provider(properties));
    }
    if (url == null) {
      throw new IllegalStateException(
          "Provider " + properties.provider() + " needs portway.ai.base-url (PORTWAY_AI_BASE_URL)");
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private static String provider(AiProperties properties) {
    return properties.provider().toLowerCase(Locale.ROOT);
  }

  @Override
  public String model() {
    return properties.model();
  }

  @Override
  public Completion complete(String systemPrompt, String userMessage) {
    String body = requestBody(systemPrompt, userMessage);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (apiKey != null && !apiKey.isBlank()) {
      request.header("Authorization", "Bearer " + apiKey);
    }

    HttpResponse<String> response = send(request.build());
    try {
      return completion(json.readTree(response.body()));
    } catch (IOException e) {
      throw new IllegalStateException("Unreadable response from " + endpoint.getHost(), e);
    }
  }

  String requestBody(String systemPrompt, String userMessage) {
    ObjectNode root = json.createObjectNode();
    root.put("model", properties.model());
    root.put("max_tokens", properties.maxTokens());
    root.put("temperature", 0);
    ArrayNode messages = root.putArray("messages");
    messages.addObject().put("role", "system").put("content", systemPrompt + SCHEMA_INSTRUCTIONS);
    messages.addObject().put("role", "user").put("content", userMessage);

    ObjectNode format = root.putObject("response_format");
    if ("json_object".equalsIgnoreCase(properties.responseFormat())) {
      format.put("type", "json_object");
    } else {
      format.put("type", "json_schema");
      ObjectNode jsonSchema = format.putObject("json_schema");
      jsonSchema.put("name", "method_body_translation");
      jsonSchema.put("strict", true);
      jsonSchema.set("schema", json.valueToTree(schema));
    }
    try {
      return json.writeValueAsString(root);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private HttpResponse<String> send(HttpRequest request) {
    long backoff = 1_000;
    for (int attempt = 0; ; attempt++) {
      HttpResponse<String> response;
      try {
        response = http.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (IOException e) {
        if (attempt >= properties.maxRetries()) {
          throw new UncheckedIOException("Could not reach " + endpoint.getHost(), e);
        }
        sleep(backoff);
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        continue;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted calling " + endpoint.getHost(), e);
      }

      int status = response.statusCode();
      if (status / 100 == 2) {
        return response;
      }
      boolean retryable = status == 429 || status == 408 || status >= 500;
      if (!retryable || attempt >= properties.maxRetries()) {
        throw new IllegalStateException(
            "HTTP " + status + " from " + endpoint.getHost() + ": " + abbreviate(response.body()));
      }
      long wait =
          response.headers().firstValue("Retry-After").flatMap(OpenAiCompatibleLlmClient::seconds).orElse(backoff);
      sleep(Math.min(wait, MAX_BACKOFF_MS));
      backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
    }
  }

  private static Completion completion(JsonNode root) {
    JsonNode usage = root.path("usage");
    long in = usage.path("prompt_tokens").asLong(0);
    long out = usage.path("completion_tokens").asLong(0);
    JsonNode choice = root.path("choices").path(0);
    if (choice.isMissingNode()) {
      throw new IllegalStateException("Response has no choices: " + abbreviate(root.toString()));
    }
    JsonNode message = choice.path("message");
    String finish = choice.path("finish_reason").asText("");
    if (finish.equals("content_filter") || (message.hasNonNull("refusal") && !message.get("refusal").asText().isBlank())) {
      return new Completion(null, in, out, true, false);
    }
    String text = message.path("content").isNull() ? null : message.path("content").asText(null);
    return new Completion(text, in, out, false, finish.equals("length"));
  }

  private static java.util.Optional<Long> seconds(String retryAfter) {
    try {
      return java.util.Optional.of(Math.round(Double.parseDouble(retryAfter.strip()) * 1000));
    } catch (NumberFormatException e) {
      return java.util.Optional.empty();
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while backing off", e);
    }
  }

  private static String abbreviate(String text) {
    if (text == null) {
      return "";
    }
    String oneLine = text.replaceAll("\\s+", " ").strip();
    return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
  }
}
