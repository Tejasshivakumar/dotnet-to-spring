package com.portway.core.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.portway.core.ir.AttributeUse;
import com.portway.core.ir.MethodDecl;
import com.portway.core.ir.PropertyDecl;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.TypeDecl;
import com.portway.core.ir.TypeKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** The IR is what every later stage reads, so it is asserted against real sample files. */
class CSharpToIrVisitorTest {

  private final CSharpSourceParser parser = new CSharpSourceParser();

  private SourceFile parse(Path file) {
    ParseResult result = parser.parseFile(file);
    assertThat(result.errors()).describedAs("syntax errors in %s", file).isEmpty();
    return new CSharpToIrVisitor(result.tokens()).toSourceFile(result);
  }

  private SourceFile parseSample(String relative) {
    return parse(sample().resolve(relative));
  }

  @Test
  void capturesEntityShape() {
    SourceFile file = parseSample("Models/Book.cs");

    assertThat(file.namespaceName()).isEqualTo("BookstoreApi.Models");
    assertThat(file.usings())
        .contains("System.ComponentModel.DataAnnotations",
            "System.ComponentModel.DataAnnotations.Schema");
    assertThat(file.types()).hasSize(1);

    TypeDecl book = file.types().get(0);
    assertThat(book.kind()).isEqualTo(TypeKind.CLASS);
    assertThat(book.name()).isEqualTo("Book");
    assertThat(book.modifiers()).contains("public");
    assertThat(book.docComment()).contains("A book held in the store's catalogue.");
    assertThat(book.hasAttribute("Table")).isTrue();
    assertThat(book.attributes().stream().filter(a -> a.name().equals("Table")).findFirst())
        .get()
        .extracting(a -> ((AttributeUse) a).firstArgUnquoted().orElseThrow())
        .isEqualTo("books");
  }

  @Test
  void capturesPropertyTypesIncludingNullabilityAndDecimal() {
    TypeDecl book = parseSample("Models/Book.cs").types().get(0);

    PropertyDecl id = property(book, "Id");
    assertThat(id.type().name()).isEqualTo("long");
    assertThat(id.hasGetter()).isTrue();
    assertThat(id.hasSetter()).isTrue();
    assertThat(id.attributes()).extracting(AttributeUse::name).contains("Key", "DatabaseGenerated");

    PropertyDecl isbn = property(book, "Isbn");
    assertThat(isbn.type().name()).isEqualTo("string");
    assertThat(isbn.type().nullable()).describedAs("string? is a nullable reference").isTrue();

    PropertyDecl title = property(book, "Title");
    assertThat(title.type().nullable()).isFalse();
    assertThat(title.initializer()).isEqualTo("string.Empty");

    PropertyDecl price = property(book, "Price");
    assertThat(price.type().name()).isEqualTo("decimal");

    PropertyDecl withdrawn = property(book, "WithdrawnOn");
    assertThat(withdrawn.type().name()).isEqualTo("DateTime");
    assertThat(withdrawn.type().nullable()).isTrue();
  }

  @Test
  void capturesNamedAttributeArguments() {
    TypeDecl book = parseSample("Models/Book.cs").types().get(0);

    AttributeUse stringLength =
        property(book, "Title").attributes().stream()
            .filter(a -> a.name().equals("StringLength"))
            .findFirst()
            .orElseThrow();

    assertThat(stringLength.positionalArgs()).containsExactly("200");
    assertThat(stringLength.namedArgs()).containsEntry("MinimumLength", "1");
  }

  @Test
  void capturesExpressionBodiedProperty() {
    TypeDecl book = parseSample("Models/Book.cs").types().get(0);

    PropertyDecl inStock = property(book, "InStock");
    assertThat(inStock.hasGetter()).isTrue();
    assertThat(inStock.hasSetter()).isFalse();
    assertThat(inStock.getterExpression()).isEqualTo("StockCount > 0");
    assertThat(inStock.initializer()).isNull();
  }

  @Test
  void capturesGenericsAndCollections() {
    TypeDecl author = parseSample("Models/Author.cs").types().get(0);

    PropertyDecl books = property(author, "Books");
    assertThat(books.type().name()).isEqualTo("ICollection");
    assertThat(books.type().typeArgs()).hasSize(1);
    assertThat(books.type().typeArgs().get(0).name()).isEqualTo("Book");
  }

  @Test
  void capturesControllerRoutingAndConstructorInjection() {
    TypeDecl controller = parseSample("Controllers/BooksController.cs").types().get(0);

    assertThat(controller.name()).isEqualTo("BooksController");
    assertThat(controller.hasAttribute("ApiController")).isTrue();
    assertThat(controller.hasBaseType("ControllerBase")).isTrue();

    assertThat(controller.attributes().stream().filter(a -> a.name().equals("Route")).findFirst())
        .get()
        .extracting(a -> ((AttributeUse) a).firstArgUnquoted().orElseThrow())
        .isEqualTo("api/[controller]");

    assertThat(controller.fields()).hasSize(1);
    assertThat(controller.fields().get(0).name()).isEqualTo("_bookService");
    assertThat(controller.fields().get(0).type().name()).isEqualTo("IBookService");
    assertThat(controller.fields().get(0).isReadonly()).isTrue();

    assertThat(controller.constructors()).hasSize(1);
    assertThat(controller.constructors().get(0).params()).hasSize(1);
  }

  @Test
  void capturesMethodSignaturesBodiesAndLineRanges() {
    TypeDecl controller = parseSample("Controllers/BooksController.cs").types().get(0);

    MethodDecl getById = method(controller, "GetById");
    assertThat(getById.isAsync()).isTrue();
    assertThat(getById.returnType().name()).isEqualTo("Task");
    assertThat(getById.returnType().typeArgs().get(0).name()).isEqualTo("ActionResult");
    assertThat(getById.attributes()).extracting(AttributeUse::name).containsExactly("HttpGet");

    assertThat(getById.params()).hasSize(1);
    assertThat(getById.params().get(0).name()).isEqualTo("id");
    assertThat(getById.params().get(0).type().name()).isEqualTo("long");
    assertThat(getById.params().get(0).attributes())
        .extracting(AttributeUse::name)
        .containsExactly("FromRoute");

    assertThat(getById.hasBody()).isTrue();
    assertThat(getById.bodyRaw())
        .describedAs("body must keep original whitespace, not ctx.getText()")
        .contains("\n")
        .contains("return NotFound();");
    assertThat(getById.startLine()).isLessThan(getById.endLine());
  }

  @Test
  void capturesInterfaceAndYieldMethod() {
    TypeDecl iface = parseSample("Services/IBookService.cs").types().get(0);
    assertThat(iface.kind()).isEqualTo(TypeKind.INTERFACE);
    assertThat(iface.methods()).extracting(MethodDecl::name)
        .contains("GetAllAsync", "CreateAsync", "TotalCatalogueValue", "StreamInStock");
    assertThat(method(iface, "GetAllAsync").hasBody()).isFalse();

    TypeDecl service = parseSample("Services/BookService.cs").types().get(0);
    assertThat(service.hasBaseType("IBookService")).isTrue();
    assertThat(method(service, "StreamInStock").bodyRaw()).contains("yield return");
  }

  @Test
  void capturesDbContextSets() {
    TypeDecl context = parseSample("Data/BookstoreDbContext.cs").types().get(0);

    assertThat(context.hasBaseType("DbContext")).isTrue();
    assertThat(property(context, "Books").type().name()).isEqualTo("DbSet");
    assertThat(property(context, "Books").type().typeArgs().get(0).name()).isEqualTo("Book");
    assertThat(method(context, "OnModelCreating").bodyRaw()).contains("HasIndex");
  }

  @Test
  void capturesEnumMembers() {
    TypeDecl genre = parseSample("Models/Genre.cs").types().get(0);

    assertThat(genre.kind()).isEqualTo(TypeKind.ENUM);
    assertThat(genre.fields()).extracting(f -> f.name())
        .containsExactly("Unknown", "Fiction", "NonFiction", "ScienceFiction", "Biography");
    assertThat(genre.fields().get(3).initializer()).isEqualTo("3");
  }

  @Test
  void handlesBlockScopedNamespacesToo() {
    ParseResult result =
        parser.parse(
            """
            namespace Outer.Inner
            {
                public class Thing
                {
                    public int Value { get; set; }
                }
            }
            """,
            "Thing.cs");
    SourceFile file = new CSharpToIrVisitor(result.tokens()).toSourceFile(result);

    assertThat(file.namespaceName()).isEqualTo("Outer.Inner");
    assertThat(file.types()).hasSize(1);
    assertThat(file.types().get(0).name()).isEqualTo("Thing");
  }

  private static PropertyDecl property(TypeDecl type, String name) {
    return type.properties().stream()
        .filter(p -> p.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no property " + name + " on " + type.name()));
  }

  private static MethodDecl method(TypeDecl type, String name) {
    return type.methods().stream()
        .filter(m -> m.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no method " + name + " on " + type.name()));
  }

  private static Path sample() {
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
