package com.portway.core.verify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MavenOutputParserTest {

  @Test
  void parsesErrorsRelativeToTheProjectRoot() {
    String output =
        """
        [INFO] Compiling 12 source files
        [ERROR] /workspace/src/main/java/com/example/Foo.java:[24,17] cannot find symbol
        [ERROR] /workspace/src/main/java/com/example/Foo.java:[24,17] cannot find symbol
        [ERROR] /workspace/src/main/java/com/example/Bar.java:[3,1] class, interface, enum, or record expected
        [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin
        """;

    assertThat(MavenOutputParser.parse(output, "/workspace"))
        .containsExactly(
            new CompileError("src/main/java/com/example/Foo.java", 24, 17, "cannot find symbol"),
            new CompileError(
                "src/main/java/com/example/Bar.java", 3, 1, "class, interface, enum, or record expected"));
  }
}
