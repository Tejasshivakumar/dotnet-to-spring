package com.portway.app.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Model responses keyed by a hash of the exact prompt and model.
 *
 * <p>Re-running a migration costs nothing and returns instantly, which is also the honest answer to
 * "is this expensive to run". Only responses that passed validation are ever stored.
 */
public interface AiCache {

  record Entry(String responseJson, long inputTokens, long outputTokens) {}

  Optional<Entry> get(String key);

  void put(String key, String model, Entry entry);

  static String key(String model, String system, String user) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(model.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(system.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(user.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** For the CLI, which has no database: a cache for one run. */
  static AiCache inMemory() {
    Map<String, Entry> entries = new ConcurrentHashMap<>();
    return new AiCache() {
      @Override
      public Optional<Entry> get(String key) {
        return Optional.ofNullable(entries.get(key));
      }

      @Override
      public void put(String key, String model, Entry entry) {
        entries.put(key, entry);
      }
    };
  }
}
