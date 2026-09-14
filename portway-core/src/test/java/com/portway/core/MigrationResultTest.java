package com.portway.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portway.core.report.Finding;
import com.portway.core.report.FindingCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationResultTest {

  /**
   * Generation runs in dependency order and the review UI lists files in that order. An immutable
   * map built with Map.copyOf hashes its keys and loses it.
   */
  @Test
  void preservesGenerationOrder() {
    Map<String, String> generated = new LinkedHashMap<>();
    generated.put("pom.xml", "");
    generated.put("src/main/resources/application.yml", "");
    generated.put("src/main/java/com/example/domain/Book.java", "");
    generated.put("src/main/java/com/example/repository/BookRepository.java", "");
    generated.put("src/main/java/com/example/controller/BooksController.java", "");
    generated.put("MIGRATION-NOTES.md", "");

    MigrationResult result = MigrationResult.of(generated, List.of());

    assertThat(result.files().keySet()).containsExactlyElementsOf(generated.keySet());
  }

  @Test
  void isUnmodifiable() {
    MigrationResult result = MigrationResult.of(Map.of("pom.xml", ""), List.of());
    assertThatThrownBy(() -> result.files().put("x", ""))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void copiesDefensivelySoLaterMutationDoesNotLeakIn() {
    Map<String, String> generated = new LinkedHashMap<>();
    generated.put("pom.xml", "");
    MigrationResult result = MigrationResult.of(generated, List.of());

    generated.put("sneaky.java", "");

    assertThat(result.files()).containsOnlyKeys("pom.xml");
  }

  @Test
  void keepsFindingOrder() {
    Finding first = Finding.at(FindingCode.ASYNC_DROPPED, "a", "A.cs", 1);
    Finding second = Finding.at(FindingCode.DECIMAL_ARITHMETIC, "b", "B.cs", 2);

    MigrationResult result = MigrationResult.of(Map.of(), List.of(first, second));

    assertThat(result.findings()).containsExactly(first, second);
  }
}
