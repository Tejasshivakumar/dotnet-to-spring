package com.portway.core;

import java.nio.file.Path;

/** Wires parse, classify, plan and generate together. */
public class DefaultMigrator implements Migrator {

  @Override
  public MigrationResult migrate(Path sourceDir) {
    throw new UnsupportedOperationException(
        "Not built yet: parser lands in week 1, entity generation in week 2");
  }
}
