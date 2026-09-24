package com.portway.app.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VerifyClasspathTest {

  /** The index and the pom's copy list are maintained by hand; this fails if they drift. */
  @Test
  void everyListedJarIsPackagedAndReadable() {
    assertThat(VerifyClasspath.listed()).contains("spring-web.jar", "jakarta.persistence-api.jar");
    assertThat(VerifyClasspath.resolve())
        .hasSameSizeAs(VerifyClasspath.listed())
        .allSatisfy(jar -> assertThat(Files.size(jar)).isPositive());
  }

  @Test
  void packagedJarsAreExactlyTheListedOnes() throws Exception {
    Path dir = VerifyClasspath.resolve().get(0).getParent();
    try (var files = Files.list(dir)) {
      assertThat(files.map(p -> p.getFileName().toString()).sorted().toList())
          .containsExactlyInAnyOrderElementsOf(VerifyClasspath.listed());
    }
  }
}
