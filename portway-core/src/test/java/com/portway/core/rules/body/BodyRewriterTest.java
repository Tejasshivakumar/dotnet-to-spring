package com.portway.core.rules.body;

import static org.assertj.core.api.Assertions.assertThat;

import com.portway.core.report.FindingCode;
import com.portway.core.rules.body.RewriteContext.Accessor;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BodyRewriterTest {

  private final BodyRewriter rewriter = new BodyRewriter();

  /** Roughly what the generator builds for the sample's BookService. */
  private static final RewriteContext SERVICE =
      new RewriteContext(
          Map.of("_db", "db", "_bookService", "bookService", "_logger", "log"),
          Map.of("_db", Map.of("Books", "Book", "Authors", "Author")),
          Map.of(
              "Id", new Accessor("getId", "setId"),
              "Title", new Accessor("getTitle", "setTitle"),
              "Name", new Accessor("getName", "setName"),
              "Author", new Accessor("getAuthor", "setAuthor"),
              "AuthorId", new Accessor("getAuthorId", "setAuthorId"),
              "AuthorName", new Accessor("getAuthorName", "setAuthorName"),
              "StockCount", new Accessor("getStockCount", "setStockCount"),
              "InStock", new Accessor("isInStock", "setInStock"),
              "Price", new Accessor("getPrice", "setPrice")),
          Set.of("Book", "Author", "BookResponse", "Genre"),
          Map.of("ToResponse", "toResponse"),
          Set.of("Price"),
          Map.of("GetById", "/api/books/{id}"),
          false,
          false,
          Set.of("_logger"),
          Map.of("Book", "Id", "Author", "Id"));

  private static final RewriteContext CONTROLLER = SERVICE.withWrapBareReturns(true);

  private String rule(String csharp) {
    return rewriter.applyRules(csharp, SERVICE).strip();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "var x = await _bookService.GetAllAsync();       | var x = bookService.getAll();",
        "return Ok(books);                               | return ResponseEntity.ok(books);",
        "return NotFound();                              | return ResponseEntity.notFound().build();",
        "return NoContent();                             | return ResponseEntity.noContent().build();",
        "return BadRequest(err);                         | return ResponseEntity.badRequest().body(err);",
        "return StatusCode(500);                         | return ResponseEntity.status(500).build();",
        "var t = book.Title;                             | var t = book.getTitle();",
        "book.Title = t;                                 | book.setTitle(t);",
        "var g = Genre.Fiction;                          | var g = Genre.Fiction;",
        "var n = list.Count;                             | var n = list.size();",
        "var e = string.Empty;                           | var e = \"\";",
        "throw new ArgumentException(m);                 | throw new IllegalArgumentException(m);",
        "throw new InvalidOperationException(m);         | throw new IllegalStateException(m);",
        "throw new KeyNotFoundException(m);              | throw new NoSuchElementException(m);",
        "throw new Exception(m);                         | throw new RuntimeException(m);",
        "if (x is null) return;                          | if (x == null) return;",
        "if (x is not null) return;                      | if (x != null) return;",
        "foreach (var b in books) { }                    | for (var b : books) { }",
        "string s = t;                                   | String s = t;",
        "bool f = true;                                  | boolean f = true;",
        "var l = new List<int>();                        | var l = new ArrayList<Integer>();",
        "Dictionary<string, int> d = null;               | Map<String, Integer> d = null;",
        "int? n = null;                                  | Integer n = null;",
        "var id = Guid.NewGuid();                        | var id = UUID.randomUUID();",
        "var u = s.ToUpper().Trim();                     | var u = s.toUpperCase().strip();",
        "var r = ToResponse(b);                          | var r = toResponse(b);",
        "var s = x.ToString();                           | var s = x.toString();",
        "if (s == \"a\") return;                         | if (\"a\".equals(s)) return;",
        "var n = a ?? b;                                 | var n = Objects.requireNonNullElse(a, b);",
      })
  void rewritesIdiom(String csharp, String java) {
    assertThat(rule(csharp)).isEqualTo(java.strip());
  }

  @Test
  void nullOrEmptyChecks() {
    assertThat(rule("var ok = string.IsNullOrEmpty(s);")).isEqualTo("var ok = (s == null || s.isEmpty());");
    assertThat(rule("var ok = string.IsNullOrWhiteSpace(s);"))
        .isEqualTo("var ok = (s == null || s.isBlank());");
  }

  @Test
  void linqChainGetsOneStreamAndOneTerminal() {
    assertThat(rule("var r = books.Where(b => b.StockCount > 0).OrderBy(b => b.Title).Select(b => ToResponse(b)).ToList();"))
        .isEqualTo(
            "var r = books.stream().filter(b -> b.getStockCount() > 0)"
                + ".sorted(Comparator.comparing(b -> b.getTitle())).map(b -> toResponse(b)).toList();");
  }

  @Test
  void linqChainSplitAcrossLines() {
    assertThat(rule("return books\n    .OrderBy(b => b.Title)\n    .Select(b => ToResponse(b))\n    .ToList();"))
        .isEqualTo(
            "return books.stream().sorted(Comparator.comparing(b -> b.getTitle())).map(b -> toResponse(b)).toList();");
  }

  @Test
  void unterminatedLinqChainIsCollectedToList() {
    assertThat(rule("return books.Where(b => b.StockCount > 0);"))
        .isEqualTo("return books.stream().filter(b -> b.getStockCount() > 0).toList();");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "var b = await _db.Books.FindAsync(id);   | var b = bookRepository.findById(id).orElse(null);",
        "_db.Books.Add(book);                     | bookRepository.save(book);",
        "_db.Books.Remove(book);                  | bookRepository.delete(book);",
        "var all = _db.Books.ToList();            | var all = bookRepository.findAll();",
        "var n = _db.Books.Count();               | var n = bookRepository.count();",
      })
  void dbSetOperationsBecomeRepositoryCalls(String csharp, String java) {
    assertThat(rule(csharp)).isEqualTo(java.strip());
  }

  @Test
  void dbSetQueryBecomesStreamOverFindAll() {
    assertThat(rule("var b = await _db.Books.Include(x => x.Author).FirstOrDefaultAsync(x => x.StockCount > n);"))
        .isEqualTo("var b = bookRepository.findAll().stream().filter(x -> x.getStockCount() > n).findFirst().orElse(null);");
  }

  @Test
  void keyLookupBecomesFindById() {
    assertThat(rule("var b = await _db.Books.Include(x => x.Author).FirstOrDefaultAsync(b => b.Id == id);"))
        .isEqualTo("var b = bookRepository.findById(id).orElse(null);");
    assertThat(rule("var a = _db.Authors.Single(a => request.AuthorId == a.Id);"))
        .isEqualTo("var a = authorRepository.findById(request.getAuthorId()).orElseThrow();");
  }

  @Test
  void loggerCallsBecomeSlf4j() {
    assertThat(rule("_logger.LogInformation(\"Created book {Id} for {Author}\", book.Id, a);"))
        .isEqualTo("log.info(\"Created book {} for {}\", book.getId(), a);");
    assertThat(rule("_logger.LogError(ex, \"Failed {Id}\", id);"))
        .isEqualTo("log.error(\"Failed {}\", id, ex);");
  }

  @Test
  void saveChangesDisappears() {
    assertThat(rule("_db.Books.Add(book);\nawait _db.SaveChangesAsync();\nreturn book;"))
        .isEqualTo("bookRepository.save(book);\nreturn book;");
  }

  @Test
  void interpolatedStringBecomesConcatenation() {
    assertThat(rule("throw new KeyNotFoundException($\"No author with id {request.AuthorId}\");"))
        .isEqualTo("throw new NoSuchElementException(\"No author with id \" + request.getAuthorId());");
    assertThat(rule("var s = $\"{a}{b}\";")).isEqualTo("var s = \"\" + a + b;");
    assertThat(rule("var s = $\"{a + 1} items\";")).isEqualTo("var s = (a + 1) + \" items\";");
  }

  @Test
  void completedTasksBecomeTheirValues() {
    assertThat(rule("return Task.FromResult(count);")).isEqualTo("return count;");
    assertThat(rule("return ValueTask.FromResult<int>(n + 1);")).isEqualTo("return n + 1;");
    assertThat(rule("return Task.CompletedTask;")).isEqualTo("return;");
  }

  @Test
  void otherTaskApisGoToTheModel() {
    RewriteResult result = rewriter.rewrite("{ await Task.Delay(10); }", SERVICE.withReturnsVoid(true), false);

    assertThat(result.tier()).isEqualTo(Tier.B);
    assertThat(result.reason()).contains("Task API");
  }

  @Test
  void stringContentsAreNeverRewritten() {
    assertThat(rule("var s = \"await _db.Books.Count => x?.Y\";"))
        .isEqualTo("var s = \"await _db.Books.Count => x?.Y\";");
  }

  @Test
  void nullConditionalWithFallbackBecomesOptional() {
    assertThat(rule("var n = book.Author?.Name ?? \"Unknown\";"))
        .isEqualTo("var n = Optional.ofNullable(book.getAuthor()).map(v -> v.getName()).orElse(\"Unknown\");");
  }

  @Test
  void objectInitializerBecomesSetters() {
    assertThat(rule("var book = new Book\n{\n    Title = request.Title,\n    AuthorId = request.AuthorId\n};"))
        .isEqualTo(
            "var book = new Book();\nbook.setTitle(request.getTitle());\nbook.setAuthorId(request.getAuthorId());");
  }

  @Test
  void returnedObjectInitializerGetsALocal() {
    assertThat(rule("return new BookResponse { Id = book.Id, InStock = book.StockCount > 0 };"))
        .isEqualTo(
            "var bookResponse = new BookResponse();\nbookResponse.setId(book.getId());\n"
                + "bookResponse.setInStock(book.getStockCount() > 0);\nreturn bookResponse;");
  }

  // --------------------------------------------------------------- tiers

  @Test
  void controllerActionIsTierA() {
    RewriteResult result =
        rewriter.rewrite(
            """
            {
                var book = await _bookService.GetByIdAsync(id);
                if (book == null)
                {
                    return NotFound();
                }

                return Ok(book);
            }""",
            CONTROLLER,
            false);

    assertThat(result.tier()).isEqualTo(Tier.A);
    assertThat(JavaImports.toSimpleNames(result.javaBody()))
        .isEqualTo(
            """
            var book = bookService.getById(id);
            if (book == null)
            {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(book);
            """);
    assertThat(JavaImports.referenced(result.javaBody()))
        .containsExactly("org.springframework.http.ResponseEntity");
    assertThat(result.notes()).contains(FindingCode.ASYNC_DROPPED);
  }

  @Test
  void createdAtActionUsesTheTargetActionsRoute() {
    RewriteResult result =
        rewriter.rewrite(
            "{ var created = await _bookService.CreateAsync(request);\n"
                + "  return CreatedAtAction(nameof(GetById), new { id = created.Id }, created); }",
            CONTROLLER,
            false);

    assertThat(result.reason()).isNull();
    assertThat(JavaImports.toSimpleNames(result.javaBody()))
        .contains(
            "ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentContextPath()"
                + ".path(\"/api/books/{id}\").buildAndExpand(created.getId()).toUri()).body(created)");
  }

  @Test
  void bareReturnInActionResultIsWrappedInOk() {
    RewriteResult result = rewriter.rewrite("{ return _bookService.TotalCatalogueValue(); }", CONTROLLER, false);

    assertThat(JavaImports.toSimpleNames(result.javaBody()).strip())
        .isEqualTo("return ResponseEntity.ok(bookService.totalCatalogueValue());");
  }

  @Test
  void yieldReturnIsTierCAndNeverSentToTheModel() {
    RewriteResult result =
        rewriter.rewrite("{ foreach (var b in _db.Books) { yield return b; } }", SERVICE, false);

    assertThat(result.tier()).isEqualTo(Tier.C);
    assertThat(result.reason()).contains("yield return");
    assertThat(result.notes()).contains(FindingCode.UNSUPPORTED_CONSTRUCT);
  }

  @Test
  void byReferenceParameterIsTierC() {
    assertThat(rewriter.rewrite("{ value = 1; return true; }", SERVICE, true).tier()).isEqualTo(Tier.C);
  }

  @Test
  void decimalArithmeticGoesToTheModelWithAFinding() {
    RewriteResult local = rewriter.rewrite("{ decimal total = 0; return total; }", SERVICE, false);
    RewriteResult member =
        rewriter.rewrite("{ return book.Price * book.StockCount; }", SERVICE, false);
    RewriteResult assignment =
        rewriter.rewrite("{ var b = new Book(); b.Price = request.Price; return b; }", SERVICE, false);

    assertThat(local.tier()).isEqualTo(Tier.B);
    assertThat(local.notes()).contains(FindingCode.DECIMAL_ARITHMETIC);
    assertThat(member.tier()).isEqualTo(Tier.B);
    // Copying a decimal is not arithmetic.
    assertThat(assignment.tier()).isEqualTo(Tier.A);
  }

  @Test
  void unrecognisedCallGoesToTheModelWithTheReason() {
    RewriteResult result = rewriter.rewrite("{ return _db.Database.CanConnect(); }", SERVICE, false);

    assertThat(result.tier()).isEqualTo(Tier.B);
    assertThat(result.reason()).contains("DbContext");
    assertThat(result.notes()).contains(FindingCode.EF_FLUENT_CONFIG);
  }

  @Test
  void switchExpressionGoesToTheModel() {
    RewriteResult result =
        rewriter.rewrite("{ return g switch { Genre.Fiction => 1, _ => 0 }; }", SERVICE, false);

    assertThat(result.tier()).isEqualTo(Tier.B);
    assertThat(result.reason()).contains("switch expression");
  }

  @Test
  void expressionBodyBecomesReturnOrStatementByReturnType() {
    assertThat(BodyRewriter.asBlock("=> x * 2", false)).isEqualTo("{\n    return x * 2;\n}");
    assertThat(BodyRewriter.asBlock("=> Log(x)", true)).isEqualTo("{\n    Log(x);\n}");
    assertThat(BodyRewriter.asBlock("=> throw new Exception()", false))
        .isEqualTo("{\n    throw new Exception();\n}");
  }

  @Test
  void bodyThatDoesNotParseAsJavaIsNotTierA() {
    // Survives every rule and the residual scan, but is not Java.
    RewriteResult result = rewriter.rewrite("{ int[] a = { 1, 2 ; }", SERVICE, false);

    assertThat(result.tier()).isEqualTo(Tier.B);
  }
}
