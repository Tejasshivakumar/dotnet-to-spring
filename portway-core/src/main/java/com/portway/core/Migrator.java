package com.portway.core;

import java.nio.file.Path;

/**
 * The whole pipeline behind one call: parse a directory of C# into the IR, classify it, apply the
 * mapping rules, and generate a Spring Boot project.
 *
 * <p>Deliberately narrow. The Spring application drives this interface and adds persistence,
 * compile verification and the AI layer around it; the core library knows about none of that.
 */
public interface Migrator {

  /**
   * Migrates every {@code .cs} file under {@code sourceDir}, plus any {@code .csproj} and {@code
   * appsettings.json} found there.
   *
   * @param sourceDir root of the ASP.NET Core project
   * @return generated file contents keyed by their path in the generated project
   */
  MigrationResult migrate(Path sourceDir);
}
