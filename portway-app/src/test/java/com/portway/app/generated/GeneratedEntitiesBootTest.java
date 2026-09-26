package com.portway.app.generated;

import static org.assertj.core.api.Assertions.assertThat;

import com.portway.app.TestPaths;
import com.portway.core.DefaultMigrator;
import com.portway.core.MigrationResult;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generated entities must be valid JPA, not just valid Java.
 *
 * <p>Compiling proves the code compiles. It does not prove Hibernate will accept the mapping: an
 * entity with no {@code @Id}, a column mapped twice for writing, or a {@code mappedBy} naming a
 * field that does not exist all compile and then fail at startup. This boots real Hibernate over
 * every generated entity, against an in-memory database, and lets it create the schema.
 */
class GeneratedEntitiesBootTest {

  static List<Path> projects() throws IOException {
    List<Path> projects = new ArrayList<>(List.of(TestPaths.sample()));
    try (Stream<Path> cases = Files.list(TestPaths.sample().getParent().getParent().resolve("testdata"))) {
      cases.filter(c -> Files.isDirectory(c.resolve("input"))).sorted().map(c -> c.resolve("input")).forEach(projects::add);
    }
    return projects;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("projects")
  void hibernateAcceptsEveryGeneratedEntity(Path project, @TempDir Path work) throws Exception {
    MigrationResult result = new DefaultMigrator().migrate(project);
    List<Path> domain = new ArrayList<>();
    for (Map.Entry<String, String> file : result.files().entrySet()) {
      if (file.getKey().matches("src/main/java/.*/domain/[^/]+\\.java")) {
        Path target = work.resolve("src").resolve(file.getKey());
        Files.createDirectories(target.getParent());
        Files.writeString(target, file.getValue());
        domain.add(target);
      }
    }
    if (domain.isEmpty()) {
      return;
    }
    Path classes = Files.createDirectories(work.resolve("classes"));
    compile(domain, classes);

    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
      List<Class<?>> entities = new ArrayList<>();
      for (Path source : domain) {
        String name = work.resolve("src/src/main/java").relativize(source).toString()
            .replace(File.separatorChar, '.').replaceAll("\\.java$", "");
        Class<?> type = loader.loadClass(name);
        if (type.isAnnotationPresent(jakarta.persistence.Entity.class)) {
          entities.add(type);
        }
      }
      assertThat(entities).describedAs("entities in %s", project).isNotEmpty();

      Thread.currentThread().setContextClassLoader(loader);
      StandardServiceRegistry registry =
          new StandardServiceRegistryBuilder()
              .applySetting("hibernate.connection.url", "jdbc:h2:mem:" + System.nanoTime() + ";MODE=PostgreSQL")
              .applySetting("hibernate.hbm2ddl.auto", "create")
              .applySetting("hibernate.show_sql", "false")
              .build();
      try {
        MetadataSources sources = new MetadataSources(registry);
        entities.forEach(sources::addAnnotatedClass);
        try (SessionFactory factory = sources.buildMetadata().buildSessionFactory()) {
          assertThat(factory.getMetamodel().getEntities()).hasSameSizeAs(entities);
        }
      } finally {
        StandardServiceRegistryBuilder.destroy(registry);
      }
    }
  }

  private static void compile(List<Path> sources, Path classes) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
      boolean ok =
          compiler.getTask(
                  null, files, diagnostics,
                  List.of("-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), "-proc:none"),
                  null, files.getJavaFileObjectsFromPaths(sources))
              .call();
      assertThat(ok)
          .describedAs(diagnostics.getDiagnostics().stream()
              .filter(d -> d.getKind() == Diagnostic.Kind.ERROR).map(Object::toString).toList().toString())
          .isTrue();
    }
  }
}
