package com.portway.core;

import com.portway.core.classify.EntityClassifier;
import com.portway.core.generate.MigrationOptions;
import com.portway.core.ir.SourceProject;
import java.nio.file.Path;
import java.util.List;

/**
 * Parse, classify and generate in one call.
 *
 * <p>A thin shell over {@link MigrationSession}, which exposes the same work stage by stage for the
 * pipeline that needs to report progress between them.
 */
public class DefaultMigrator implements Migrator {

  private final MigrationOptions options;

  public DefaultMigrator() {
    this(MigrationOptions.defaults());
  }

  public DefaultMigrator(MigrationOptions options) {
    this.options = options;
  }

  @Override
  public MigrationResult migrate(Path sourceDir) {
    return new MigrationSession(sourceDir, options).run();
  }

  /** Entity names discovered from DbSets, exposed for callers that want the plan before running. */
  public static List<String> entityNames(SourceProject project) {
    return List.copyOf(EntityClassifier.entityNames(project));
  }
}
