package com.portway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.portway.core.ir.TypeRef;
import com.portway.core.report.FindingCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TypeMapperTest {

  private final TypeMapper mapper = TypeMapper.fromDefaults();

  private JavaType map(String csharp) {
    return mapper.map(TypeRef.of(csharp)).type();
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "int,      int",
    "long,     long",
    "short,    short",
    "bool,     boolean",
    "char,     char",
    "double,   double",
    "float,    float",
    "string,   String",
    "object,   Object",
    "void,     void",
    "decimal,  BigDecimal",
    "DateTime, LocalDateTime",
    "DateTimeOffset, OffsetDateTime",
    "DateOnly, LocalDate",
    "TimeOnly, LocalTime",
    "TimeSpan, Duration",
    "Guid,     UUID",
  })
  void mapsScalars(String csharp, String expected) {
    assertThat(map(csharp).displayName()).isEqualTo(expected);
  }

  @Test
  void mapsDecimalToBigDecimalNeverDouble() {
    JavaType price = map("decimal");
    assertThat(price.qualifiedName()).isEqualTo("java.math.BigDecimal");
    assertThat(price.needsImport()).isTrue();
  }

  @Test
  void boxesNullableValueTypes() {
    TypeRef nullableInt = new TypeRef("int", List.of(), true, 0);
    assertThat(mapper.map(nullableInt).type().displayName()).isEqualTo("Integer");
    assertThat(mapper.map(nullableInt).type().primitive()).isFalse();

    TypeRef nullableDate = new TypeRef("DateTime", List.of(), true, 0);
    assertThat(mapper.map(nullableDate).type().displayName()).isEqualTo("LocalDateTime");
  }

  @Test
  void nullableReferenceTypeKeepsItsTypeButRaisesANote() {
    TypeRef nullableString = new TypeRef("string", List.of(), true, 0);
    assertThat(mapper.map(nullableString).type().displayName()).isEqualTo("String");

    TypeRef nullableDto = new TypeRef("BookDto", List.of(), true, 0);
    MappedType mapped = mapper.map(nullableDto);
    assertThat(mapped.type().displayName()).isEqualTo("BookDto");
    assertThat(mapped.notes()).contains(FindingCode.NULLABLE_REFERENCE);
  }

  @ParameterizedTest(name = "{0}<Book> -> {1}")
  @CsvSource({
    "List,          List<Book>",
    "IList,         List<Book>",
    "IEnumerable,   List<Book>",
    "IReadOnlyList, List<Book>",
    "ICollection,   Collection<Book>",
    "HashSet,       Set<Book>",
    "ISet,          Set<Book>",
  })
  void mapsGenericContainers(String csharp, String expected) {
    TypeRef ref = TypeRef.generic(csharp, TypeRef.of("Book"));
    assertThat(mapper.map(ref).type().displayName()).isEqualTo(expected);
  }

  @Test
  void mapsDictionaryToMap() {
    TypeRef ref = TypeRef.generic("Dictionary", TypeRef.of("string"), TypeRef.of("int"));
    assertThat(mapper.map(ref).type().displayName()).isEqualTo("Map<String, Integer>");
  }

  @Test
  void boxesGenericArgumentsBecauseJavaHasNoListOfInt() {
    TypeRef ref = TypeRef.generic("List", TypeRef.of("int"));
    assertThat(mapper.map(ref).type().displayName()).isEqualTo("List<Integer>");
  }

  @Test
  void unwrapsTaskAndRaisesAsyncDropped() {
    TypeRef ref = TypeRef.generic("Task", TypeRef.of("string"));
    MappedType mapped = mapper.map(ref);

    assertThat(mapped.type().displayName()).isEqualTo("String");
    assertThat(mapped.notes()).containsExactly(FindingCode.ASYNC_DROPPED);
  }

  @Test
  void bareTaskBecomesVoid() {
    MappedType mapped = mapper.map(TypeRef.of("Task"));
    assertThat(mapped.type()).isEqualTo(JavaType.VOID);
    assertThat(mapped.notes()).containsExactly(FindingCode.ASYNC_DROPPED);
  }

  @Test
  void unwrapsNestedTaskOfList() {
    TypeRef ref = TypeRef.generic("Task", TypeRef.generic("List", TypeRef.of("BookResponse")));
    MappedType mapped = mapper.map(ref);

    assertThat(mapped.type().displayName()).isEqualTo("List<BookResponse>");
    assertThat(mapped.notes()).containsExactly(FindingCode.ASYNC_DROPPED);
  }

  @Test
  void mapsActionResults() {
    assertThat(map("IActionResult").displayName()).isEqualTo("ResponseEntity<?>");

    TypeRef typed = TypeRef.generic("ActionResult", TypeRef.of("BookResponse"));
    assertThat(mapper.map(typed).type().displayName()).isEqualTo("ResponseEntity<BookResponse>");
  }

  @Test
  void mapsTaskOfActionResultOfList() {
    TypeRef ref =
        TypeRef.generic(
            "Task",
            TypeRef.generic("ActionResult", TypeRef.generic("List", TypeRef.of("BookResponse"))));

    MappedType mapped = mapper.map(ref);

    assertThat(mapped.type().displayName()).isEqualTo("ResponseEntity<List<BookResponse>>");
    assertThat(mapped.notes()).containsExactly(FindingCode.ASYNC_DROPPED);
  }

  @Test
  void unsignedByteRaisesANoteBecauseJavaBytesAreSigned() {
    assertThat(mapper.map(TypeRef.of("byte")).notes()).contains(FindingCode.UNSIGNED_BYTE);
  }

  @Test
  void mapsArrays() {
    TypeRef byteArray = new TypeRef("byte", List.of(), false, 1);
    assertThat(mapper.map(byteArray).type().displayName()).isEqualTo("byte[]");

    TypeRef stringArray = new TypeRef("string", List.of(), false, 1);
    assertThat(mapper.map(stringArray).type().displayName()).isEqualTo("String[]");
  }

  @Test
  void passesProjectTypesThroughUnchanged() {
    assertThat(map("BookDto").displayName()).isEqualTo("BookDto");
    assertThat(map("BookDto").needsImport()).isFalse();
  }

  @Test
  void collapsesNullableOfT() {
    TypeRef ref = TypeRef.generic("Nullable", TypeRef.of("int"));
    assertThat(mapper.map(ref).type().displayName()).isEqualTo("Integer");
  }

  @Test
  void primitivesAreNeverImported() {
    assertThat(map("int").needsImport()).isFalse();
    assertThat(map("string").needsImport()).describedAs("java.lang is implicit").isFalse();
    assertThat(map("decimal").needsImport()).isTrue();
  }

  @Test
  void promptTableStaysInSyncWithTheEngine() {
    String table = mapper.asPromptTable();
    assertThat(table).contains("decimal -> java.math.BigDecimal");
    assertThat(table).contains("Task<T> -> T (drop await)");
  }
}
