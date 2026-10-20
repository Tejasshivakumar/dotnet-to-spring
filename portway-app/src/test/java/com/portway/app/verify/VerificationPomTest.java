package com.portway.app.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portway.app.TestPaths;
import com.portway.core.DefaultMigrator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerificationPomTest {
  @TempDir Path project;

  @Test
  void acceptsTheGeneratedPom() throws Exception {
    Path pom = write(generatedPom());
    assertThatCode(() -> VerificationPom.validate(pom)).doesNotThrowAnyException();
  }

  @Test
  void refusesExecutableBuildCustomizationsBeforeStartingDocker() throws Exception {
    String original = generatedPom();
    for (String modified : java.util.List.of(
        original.replace("</plugins>", "<plugin><groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId></plugin></plugins>"),
        original.replace("</project>", "<profiles><profile><id>custom</id></profile></profiles></project>"),
        original.replace("spring-boot-starter-parent", "custom-parent"),
        original.replace("<relativePath/>", "<relativePath>../pom.xml</relativePath>"),
        original.replace("</project>", "<repositories/></project>"))) {
      write(modified);
      var result = new DockerCompileVerifier(project.resolve("cache"), Duration.ofSeconds(1), 1024).verify(project);
      assertThat(result.success()).isFalse();
      assertThat(result.errors()).singleElement().satisfies(error ->
          assertThat(error.message()).contains("requires the generated Spring Boot parent"));
      assertThat(project.resolve("cache")).doesNotExist();
    }
  }

  @Test
  void refusesExternalEntities() throws Exception {
    Path pom = write("<!DOCTYPE project [<!ENTITY x SYSTEM 'file:///nonexistent'>]><project>&x;</project>");
    assertThatThrownBy(() -> VerificationPom.validate(pom)).isInstanceOf(IllegalArgumentException.class);
  }

  private Path write(String xml) throws Exception {
    return Files.writeString(project.resolve("pom.xml"), xml);
  }

  private String generatedPom() {
    return new DefaultMigrator().migrate(TestPaths.sample()).files().get("pom.xml");
  }
}
