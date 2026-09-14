package com.portway.core.generate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NamesTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "Title,        title",
    "Id,           id",
    "StockCount,   stockCount",
    "ISBN,         isbn",
    "IDNumber,     idNumber",
    "A,            a",
  })
  void camelCasesPropertyNames(String property, String expected) {
    assertThat(Names.fieldName(property)).isEqualTo(expected);
  }

  @Test
  void buildsAccessorNames() {
    assertThat(Names.getterName("title", false)).isEqualTo("getTitle");
    assertThat(Names.getterName("inStock", true)).isEqualTo("isInStock");
    assertThat(Names.setterName("title")).isEqualTo("setTitle");
  }

  /** The Async suffix is a C# convention that means nothing once the method is synchronous. */
  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "GetAllAsync,  getAll",
    "CreateAsync,  create",
    "TotalValue,   totalValue",
    "Async,        async",
  })
  void convertsMethodNames(String csharp, String expected) {
    assertThat(Names.methodName(csharp)).isEqualTo(expected);
  }

  @Test
  void stripsInterfacePrefix() {
    assertThat(Names.stripInterfacePrefix("IBookService")).isEqualTo("BookService");
    assertThat(Names.stripInterfacePrefix("Invoice"))
        .describedAs("a lowercase second letter means this is not the I-prefix convention")
        .isEqualTo("Invoice");
    assertThat(Names.stripInterfacePrefix("IO")).isEqualTo("IO");
  }

  /**
   * The root segment only: Bookstore.Models and Bookstore.Controllers must land in one package
   * tree organised by role, not mirror the C# folder layout.
   */
  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "Bookstore.Models,        bookstore",
    "BookstoreApi.Data,       bookstoreapi",
    "Contoso.Retail.Api,      contoso",
    "My-Company.Api,          mycompany",
  })
  void derivesBasePackageFromRootNamespace(String namespaceName, String expected) {
    assertThat(Names.basePackage(namespaceName)).isEqualTo(expected);
  }

  @Test
  void fallsBackWhenTheNamespaceIsUnusable() {
    assertThat(Names.basePackage(null)).isEqualTo("migrated");
    assertThat(Names.basePackage("")).isEqualTo("migrated");
    assertThat(Names.basePackage("...")).isEqualTo("migrated");
  }

  @Test
  void prefixesSegmentsThatWouldStartWithADigit() {
    assertThat(Names.sanitiseSegment("7Eleven")).isEqualTo("_7eleven");
  }
}
