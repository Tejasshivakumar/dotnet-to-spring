package com.portway.app.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portway.app.domain.AiCacheEntry;
import com.portway.app.repo.AiCacheRepository;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;

/** The {@code ai_cache} table. Shared by every job and every instance of the application. */
public class JpaAiCache implements AiCache {

  private final AiCacheRepository repository;
  private final ObjectMapper json = new ObjectMapper();

  public JpaAiCache(AiCacheRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<Entry> get(String key) {
    return repository
        .findById(key)
        .map(e -> {
          try {
            return new Entry(
                json.writeValueAsString(e.getResponseJson()),
                e.getInputTokens() == null ? 0 : e.getInputTokens(),
                e.getOutputTokens() == null ? 0 : e.getOutputTokens());
          } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
          }
        });
  }

  @Override
  @SuppressWarnings("unchecked")
  public void put(String key, String model, Entry entry) {
    try {
      Map<String, Object> response = json.readValue(entry.responseJson(), Map.class);
      repository.save(
          new AiCacheEntry(key, model, response, (int) entry.inputTokens(), (int) entry.outputTokens()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    } catch (DataIntegrityViolationException e) {
      // Two jobs translated the same method at once. Either answer is fine.
    }
  }
}
