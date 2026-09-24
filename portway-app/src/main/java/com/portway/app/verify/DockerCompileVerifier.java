package com.portway.app.verify;

import com.portway.core.verify.CompileError;
import com.portway.core.verify.CompileResult;
import com.portway.core.verify.CompileVerifier;
import com.portway.core.verify.MavenOutputParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Compiles a generated project with a real Maven build inside a throwaway container.
 *
 * <p>Generated code is untrusted input, so the build runs with:
 *
 * <ul>
 *   <li>no network: generated code cannot reach anything, and neither can its build plugins;
 *   <li>a copy of the project, not a mount: the container cannot touch the host's output;
 *   <li>memory and CPU caps and a hard timeout: a pathological file cannot take the host down.
 * </ul>
 *
 * <p>An offline build needs every dependency already in the local repository. That is what the
 * warm-up does: once per distinct pom, a separate container with network access resolves the
 * dependencies into a cache directory, and only then does the sandboxed compile run against it.
 * The warm-up downloads artifacts; it never compiles or runs generated code.
 */
public class DockerCompileVerifier implements CompileVerifier {

  private static final DockerImageName MAVEN = DockerImageName.parse("maven:3.9-eclipse-temurin-21");
  private static final String WORKSPACE = "/workspace";

  private final Path mavenCache;
  private final Duration timeout;
  private final long memoryBytes;

  public DockerCompileVerifier(Path mavenCache, Duration timeout, long memoryBytes) {
    this.mavenCache = mavenCache;
    this.timeout = timeout;
    this.memoryBytes = memoryBytes;
  }

  @Override
  public String name() {
    return "maven in docker";
  }

  @Override
  public CompileResult verify(Path projectDir) {
    long started = System.nanoTime();
    try {
      Files.createDirectories(mavenCache);
      warmUp(projectDir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    Run run = run(projectDir, List.of("mvn", "-o", "-q", "-B", "-DskipTests", "compile"), false);
    List<CompileError> errors = MavenOutputParser.parse(run.log(), WORKSPACE);
    boolean success = run.succeeded() && errors.isEmpty();
    if (!run.succeeded() && errors.isEmpty()) {
      // The build failed for a reason the compiler did not report: a missing
      // dependency, a timeout. Still a failure, with the log as the evidence.
      errors = List.of(new CompileError(null, 0, 0, "Build failed: " + lastLines(run.log())));
    }
    return new CompileResult(success, errors, run.log(), Duration.ofNanos(System.nanoTime() - started), name());
  }

  /** Resolves the pom's dependencies once, with network, into the shared cache. */
  private void warmUp(Path projectDir) throws IOException {
    Path marker = mavenCache.resolve(".warm-" + sha256(Files.readString(projectDir.resolve("pom.xml"))));
    if (Files.exists(marker)) {
      return;
    }
    Run run = run(projectDir, List.of("mvn", "-q", "-B", "dependency:go-offline", "compile", "-DskipTests",
        "-Dmaven.main.skip=true"), true);
    if (!run.succeeded()) {
      throw new IllegalStateException("Could not resolve dependencies for the generated pom:\n" + lastLines(run.log()));
    }
    Files.writeString(marker, "");
  }

  private record Run(boolean succeeded, String log) {}

  private Run run(Path projectDir, List<String> command, boolean network) {
    // Streamed while the build runs: once a failed one-shot container is cleaned
    // up, its log is gone, and the log is the only evidence of what failed.
    StringBuffer log = new StringBuffer();
    try (GenericContainer<?> maven = new GenericContainer<>(MAVEN)) {
      maven
          .withLogConsumer(frame -> log.append(frame.getUtf8String()))
          .withCopyToContainer(MountableFile.forHostPath(projectDir), WORKSPACE)
          .withFileSystemBind(mavenCache.toString(), "/root/.m2", BindMode.READ_WRITE)
          .withWorkingDirectory(WORKSPACE)
          .withCommand(command.toArray(String[]::new))
          .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(timeout))
          .withCreateContainerCmdModifier(
              cmd -> {
                cmd.getHostConfig().withMemory(memoryBytes).withCpuQuota(100_000L);
                if (!network) {
                  cmd.getHostConfig().withNetworkMode("none");
                }
              });
      boolean succeeded = true;
      try {
        maven.start();
      } catch (ContainerLaunchException e) {
        succeeded = false;
      }
      return new Run(succeeded, log.toString());
    }
  }

  private static String lastLines(String log) {
    String[] lines = log.split("\n");
    int from = Math.max(0, lines.length - 15);
    return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
