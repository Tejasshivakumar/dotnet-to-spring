package com.portway.app.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.portway.app.TestPaths;
import com.portway.core.DefaultMigrator;
import com.portway.core.verify.CompileResult;
import com.portway.core.verify.ProjectWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The real Maven build in a sandbox. Skipped, not failed, where Docker is unavailable: CI runners
 * have it, and the in-process verifier covers the same ground elsewhere with a weaker guarantee.
 */
@Testcontainers(disabledWithoutDocker = true)
class DockerCompileVerifierTest {

  private static final Path CACHE = Path.of(System.getProperty("user.home"), ".portway", "m2");

  private final DockerCompileVerifier verifier =
      new DockerCompileVerifier(CACHE, Duration.ofMinutes(5), 1024L * 1024 * 1024);

  @Test
  void generatedSampleCompilesInTheSandbox(@TempDir Path project) {
    ProjectWriter.write(new DefaultMigrator().migrate(TestPaths.sample()).files(), project);

    CompileResult result = verifier.verify(project);

    assertThat(result.success()).describedAs(result.log()).isTrue();
  }

  @Test
  void compileErrorsAreMappedBackToGeneratedPaths(@TempDir Path project) throws Exception {
    Map<String, String> files =
        new LinkedHashMap<>(new DefaultMigrator().migrate(TestPaths.sample()).files());
    String path = "src/main/java/bookstoreapi/domain/Genre.java";
    files.put(path, files.get(path).replace("public enum Genre {", "public enum Genre { BROKEN BROKEN"));
    ProjectWriter.write(files, project);

    CompileResult result = verifier.verify(project);

    assertThat(result.success()).isFalse();
    assertThat(result.errors()).anySatisfy(e -> assertThat(e.path()).isEqualTo(path));
    assertThat(Files.exists(project.resolve("target"))).describedAs("the build ran on a copy").isFalse();
  }
}
