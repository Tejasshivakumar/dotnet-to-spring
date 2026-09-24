package com.portway.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One cached LLM response, keyed by SHA-256 of the exact prompt. */
@Entity
@Table(name = "ai_cache")
public class AiCacheEntry {

  @Id
  @Column(name = "prompt_hash")
  private String promptHash;

  @Column(nullable = false)
  private String model;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_json", nullable = false)
  private Map<String, Object> responseJson;

  @Column(name = "input_tokens")
  private Integer inputTokens;

  @Column(name = "output_tokens")
  private Integer outputTokens;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AiCacheEntry() {}

  public AiCacheEntry(
      String promptHash, String model, Map<String, Object> responseJson, Integer inputTokens, Integer outputTokens) {
    this.promptHash = promptHash;
    this.model = model;
    this.responseJson = responseJson;
    this.inputTokens = inputTokens;
    this.outputTokens = outputTokens;
    this.createdAt = Instant.now();
  }

  public String getPromptHash() {
    return promptHash;
  }

  public String getModel() {
    return model;
  }

  public Map<String, Object> getResponseJson() {
    return responseJson;
  }

  public Integer getInputTokens() {
    return inputTokens;
  }

  public Integer getOutputTokens() {
    return outputTokens;
  }
}
