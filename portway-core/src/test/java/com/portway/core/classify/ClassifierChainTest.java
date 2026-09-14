package com.portway.core.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.portway.core.ir.ClassRole;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import com.portway.core.parse.ProjectLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Classification is asserted against the real sample project, not synthetic types. */
class ClassifierChainTest {

  private static SourceProject classified;

  @BeforeAll
  static void loadAndClassify() {
    ProjectLoader.Load load = new ProjectLoader().load(sampleRoot());
    assertThat(load.errors()).isEmpty();
    classified = ClassifierChain.defaults().classify(load.project());
  }

  private static ClassRole roleOf(String typeName) {
    return classified
        .allTypes()
        .filter(t -> t.name().equals(typeName))
        .map(TypeDecl::role)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no type named " + typeName));
  }

  @Test
  void loadsTheWholeProject() {
    assertThat(classified.name()).isEqualTo("BookstoreApi");
    assertThat(classified.targetFramework()).isEqualTo("net8.0");
    assertThat(classified.packages()).extracting(p -> p.id())
        .contains("Microsoft.EntityFrameworkCore", "Npgsql.EntityFrameworkCore.PostgreSQL",
            "Swashbuckle.AspNetCore");
    assertThat(classified.appSettings()).containsKey("ConnectionStrings");
  }

  @Test
  void storesPathsRelativeToTheProjectRoot() {
    assertThat(classified.files()).extracting(f -> f.path())
        .contains("Models/Book.cs", "Controllers/BooksController.cs")
        .allSatisfy(p -> assertThat(p).doesNotStartWith("/"));
  }

  @Test
  void classifiesControllers() {
    assertThat(roleOf("BooksController")).isEqualTo(ClassRole.CONTROLLER);
    assertThat(roleOf("AuthorsController")).isEqualTo(ClassRole.CONTROLLER);
  }

  @Test
  void classifiesDbContext() {
    assertThat(roleOf("BookstoreDbContext")).isEqualTo(ClassRole.DB_CONTEXT);
  }

  /** Book and Author are entities because the DbContext exposes DbSets of them. */
  @Test
  void classifiesEntitiesFromDbSets() {
    assertThat(roleOf("Book")).isEqualTo(ClassRole.ENTITY);
    assertThat(roleOf("Author")).isEqualTo(ClassRole.ENTITY);
    assertThat(EntityClassifier.entityNames(classified)).containsExactly("Book", "Author");
  }

  @Test
  void classifiesServicesAndTheirInterfaces() {
    assertThat(roleOf("IBookService")).isEqualTo(ClassRole.SERVICE_INTERFACE);
    assertThat(roleOf("BookService")).isEqualTo(ClassRole.SERVICE);
    assertThat(roleOf("IAuthorService")).isEqualTo(ClassRole.SERVICE_INTERFACE);
    assertThat(roleOf("AuthorService")).isEqualTo(ClassRole.SERVICE);
  }

  @Test
  void classifiesDtos() {
    assertThat(roleOf("CreateBookRequest")).isEqualTo(ClassRole.DTO);
    assertThat(roleOf("BookResponse")).isEqualTo(ClassRole.DTO);
  }

  @Test
  void leavesPlainEnumsUnknownRatherThanGuessing() {
    assertThat(roleOf("Genre")).isEqualTo(ClassRole.UNKNOWN);
  }

  /** Program.cs is never parsed, but its registrations are still recovered. */
  @Test
  void recoversDiRegistrationsFromProgramCs() {
    ProgramScanner.Registrations registrations = ProgramScanner.registrations(classified);

    assertThat(registrations.interfaceToImplementation())
        .containsEntry("IBookService", "BookService")
        .containsEntry("IAuthorService", "AuthorService");
    assertThat(registrations.dbContexts()).containsExactly("BookstoreDbContext");
  }

  @Test
  void scannerHandlesTransientAndSingletonAndSelfRegistration() {
    ProgramScanner.Registrations registrations =
        ProgramScanner.scan(
            """
            builder.Services.AddTransient<IClock, SystemClock>();
            builder.Services.AddSingleton<ICache, MemoryCache>();
            builder.Services.AddScoped<AuditService>();
            builder.Services.AddDbContext<AppDbContext>(o => o.UseNpgsql(cs));
            """);

    assertThat(registrations.interfaceToImplementation())
        .containsEntry("IClock", "SystemClock")
        .containsEntry("ICache", "MemoryCache");
    assertThat(registrations.implementations()).contains("AuditService");
    assertThat(registrations.dbContexts()).containsExactly("AppDbContext");
  }

  @Test
  void scannerReturnsEmptyWhenThereIsNoEntryPoint() {
    SourceProject none =
        new SourceProject("x", "net8.0", java.util.List.of(), java.util.List.of(), java.util.Map.of());
    assertThat(ProgramScanner.registrations(none).dbContexts()).isEmpty();
  }

  private static Path sampleRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 5 && dir != null; i++) {
      Path candidate = dir.resolve("sample-dotnet/BookstoreApi");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("Cannot find sample-dotnet/BookstoreApi");
  }
}
