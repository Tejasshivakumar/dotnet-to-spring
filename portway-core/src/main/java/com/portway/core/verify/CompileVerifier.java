package com.portway.core.verify;

import java.nio.file.Path;

/**
 * Compiles a generated project and reports what failed.
 *
 * <p>Two implementations exist because two environments do. Locally and in CI, a Maven build in a
 * network-isolated container is the real check. In the cloud deployment there is no Docker socket,
 * so an in-process javac run against a fixed classpath stands in, with its weaker guarantee stated
 * in {@link CompileResult#verifier()}.
 */
public interface CompileVerifier {

  /** Compiles the Maven project rooted at {@code projectDir}. */
  CompileResult verify(Path projectDir);

  /** A short name for reports, such as {@code in-process (limited classpath)}. */
  String name();
}
