package com.portway.core.golden;

import com.portway.core.DefaultMigrator;
import com.portway.core.MigrationResult;
import com.portway.core.Migrator;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The backbone of the test suite. Every supported C# construct earns a directory under {@code
 * testdata/} with the exact Java it should produce.
 *
 * <p>A transpiler tested any other way is a transpiler debugged by reading its output and hoping.
 */
@Disabled(
    "Expectations are written ahead of the generator, which lands in week 2. "
    + "Delete this annotation, not the assertions, when DefaultMigrator is built.")
class GoldenFileTest {

  private final Migrator migrator = new DefaultMigrator();

  static List<GoldenCase> goldenCases() {
    return GoldenCases.all();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("goldenCases")
  void migratesCorrectly(GoldenCase goldenCase) {
    MigrationResult result = migrator.migrate(goldenCase.input());
    GoldenFileAssert.matchesGolden(result.files(), goldenCase);
  }
}
