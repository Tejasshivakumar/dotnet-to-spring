package com.portway.core.generate;

import static org.assertj.core.api.Assertions.assertThat;

import com.portway.core.DefaultMigrator;
import com.portway.core.MigrationResult;
import com.portway.core.report.FindingCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** What the migrator produces from the sample project, asserted rather than eyeballed. */
class EntityGeneratorTest {

  private static MigrationResult result;

  @BeforeAll
  static void migrate() {
    result = new DefaultMigrator().migrate(sampleRoot());
  }

  private static String file(String path) {
    String content = result.files().get(path);
    assertThat(content).describedAs("no generated file at %s", path).isNotNull();
    return content;
  }

  @Test
  void generatesEveryRoleInDependencyOrder() {
    // Generation order is dependency order: build files first, then enums before
    // the entities that use them, then the repositories over those entities.
    assertThat(result.files().keySet())
        .containsExactly(
            "pom.xml",
            "src/main/resources/application.yml",
            "src/main/java/bookstoreapi/domain/Genre.java",
            "src/main/java/bookstoreapi/domain/Author.java",
            "src/main/java/bookstoreapi/domain/Book.java",
            "src/main/java/bookstoreapi/repository/BookRepository.java",
            "src/main/java/bookstoreapi/repository/AuthorRepository.java",
            "src/main/java/bookstoreapi/dto/BookResponse.java",
            "src/main/java/bookstoreapi/dto/CreateBookRequest.java",
            "src/main/java/bookstoreapi/service/AuthorService.java",
            "src/main/java/bookstoreapi/service/BookService.java",
            "src/main/java/bookstoreapi/service/AuthorServiceImpl.java",
            "src/main/java/bookstoreapi/service/BookServiceImpl.java",
            "src/main/java/bookstoreapi/controller/AuthorsController.java",
            "src/main/java/bookstoreapi/controller/BooksController.java",
            "src/main/java/bookstoreapi/MigratedApplication.java",
            "MIGRATION-NOTES.md");
  }

  /** Enums carry no role, but an entity referencing one does not compile without it. */
  @Test
  void generatesReferencedEnums() {
    assertThat(file("src/main/java/bookstoreapi/domain/Genre.java"))
        .contains("public enum Genre")
        .contains("ScienceFiction");
  }

  @Test
  void mapsEntityAndTableAnnotations() {
    assertThat(file("src/main/java/bookstoreapi/domain/Book.java"))
        .contains("@Entity")
        .contains("@Table(name = \"books\")");
  }

  /** Boxed, because JpaRepository<Book, long> does not compile and null means "unsaved". */
  @Test
  void identifiersAreBoxed() {
    assertThat(file("src/main/java/bookstoreapi/domain/Book.java"))
        .contains("@Id")
        .contains("@GeneratedValue(strategy = GenerationType.IDENTITY)")
        .contains("private Long id;");
  }

  @Test
  void decimalBecomesBigDecimalNeverDouble() {
    assertThat(file("src/main/java/bookstoreapi/domain/Book.java"))
        .contains("private BigDecimal price;")
        .doesNotContain("double price");
  }

  @Test
  void mergesValidationAndColumnAttributes() {
    assertThat(file("src/main/java/bookstoreapi/domain/Book.java"))
        .contains("@NotNull")
        .contains("@Size(min = 1, max = 200)")
        .contains("@Column(name = \"title\", length = 200)");
  }

  @Test
  void enumPropertiesArePersistedAsStringsNotOrdinals() {
    assertThat(file("src/main/java/bookstoreapi/domain/Book.java"))
        .contains("@Enumerated(EnumType.STRING)");
  }

  /** The association owns the join column; the scalar that shadows it must be read-only. */
  @Test
  void mapsManyToOneWithTheJoinColumnOnTheAssociation() {
    String book = file("src/main/java/bookstoreapi/domain/Book.java");

    assertThat(book)
        .contains("@ManyToOne(fetch = FetchType.LAZY)")
        .contains("@JoinColumn(name = \"author_id\")")
        .contains("private Author author;");
    assertThat(book)
        .describedAs("the shadow scalar must not also own the column")
        .contains("@Column(name = \"author_id\", insertable = false, updatable = false)");
  }

  @Test
  void mapsOneToManyWithMappedBy() {
    assertThat(file("src/main/java/bookstoreapi/domain/Author.java"))
        .contains("@OneToMany(mappedBy = \"author\", fetch = FetchType.LAZY)");
  }

  /** Expression-bodied C# properties are computed: a @Transient getter and no field. */
  @Test
  void computedPropertiesBecomeTransientGetters() {
    String book = file("src/main/java/bookstoreapi/domain/Book.java");

    assertThat(book).contains("@Transient").contains("return stockCount > 0;");
    assertThat(book).doesNotContain("private boolean inStock;");
  }

  @Test
  void convertsXmlDocCommentsToJavadoc() {
    assertThat(file("src/main/java/bookstoreapi/domain/Book.java"))
        .contains("/** A book held in the store's catalogue. */")
        .doesNotContain("<summary>");
  }

  @Test
  void generatesRepositoryPerDbSetWithTheEntityIdType() {
    assertThat(file("src/main/java/bookstoreapi/repository/BookRepository.java"))
        .contains("public interface BookRepository extends JpaRepository<Book, Long>")
        .contains("import bookstoreapi.domain.Book;");
  }

  @Test
  void raisesFindingsForLossyMappingsAndUntranslatedConfiguration() {
    assertThat(result.findings()).extracting(f -> f.code())
        .contains(FindingCode.NULLABLE_REFERENCE, FindingCode.EF_FLUENT_CONFIG);
  }

  @Test
  void derivesTheBasePackageFromTheCsharpRootNamespace() {
    assertThat(file("src/main/java/bookstoreapi/domain/Book.java"))
        .startsWith("package bookstoreapi.domain;");
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
