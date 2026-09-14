package com.portway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.portway.core.ir.AttributeUse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttributeMapperTest {

  private final AttributeMapper mapper = new AttributeMapper();

  private List<JavaAnnotation> map(AttributeUse attribute) {
    return mapper.map(attribute).orElseThrow().annotations();
  }

  private static String render(JavaAnnotation a) {
    if (a.members().isEmpty()) {
      return "@" + a.simpleName();
    }
    if (a.members().size() == 1 && a.members().containsKey("value")) {
      return "@" + a.simpleName() + "(" + a.members().get("value") + ")";
    }
    StringBuilder sb = new StringBuilder("@").append(a.simpleName()).append('(');
    boolean first = true;
    for (Map.Entry<String, String> e : a.members().entrySet()) {
      if (!first) {
        sb.append(", ");
      }
      sb.append(e.getKey()).append(" = ").append(e.getValue());
      first = false;
    }
    return sb.append(')').toString();
  }

  @Test
  void mapsKeyToId() {
    assertThat(map(AttributeUse.of("Key"))).singleElement().extracting(JavaAnnotation::simpleName)
        .isEqualTo("Id");
  }

  @Test
  void mapsIdentityToGeneratedValue() {
    List<JavaAnnotation> annotations =
        map(AttributeUse.of("DatabaseGenerated", "DatabaseGeneratedOption.Identity"));

    assertThat(render(annotations.get(0)))
        .isEqualTo("@GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)");
  }

  @Test
  void doesNotEmitGeneratedValueForNone() {
    assertThat(map(AttributeUse.of("DatabaseGenerated", "DatabaseGeneratedOption.None"))).isEmpty();
  }

  @Test
  void mapsTableAndColumnNames() {
    assertThat(render(map(AttributeUse.of("Table", "\"books\"")).get(0)))
        .isEqualTo("@Table(name = \"books\")");
    assertThat(render(map(AttributeUse.of("Column", "\"title\"")).get(0)))
        .isEqualTo("@Column(name = \"title\")");
  }

  @Test
  void mapsColumnTypeNameToColumnDefinition() {
    AttributeUse column =
        new AttributeUse(
            "Column", List.of("\"price\""), Map.of("TypeName", "\"numeric(10,2)\""));

    assertThat(render(map(column).get(0)))
        .isEqualTo("@Column(name = \"price\", columnDefinition = \"numeric(10,2)\")");
  }

  @Test
  void mapsForeignKeyToSnakeCasedJoinColumn() {
    assertThat(render(map(AttributeUse.of("ForeignKey", "\"AuthorId\"")).get(0)))
        .isEqualTo("@JoinColumn(name = \"author_id\")");
  }

  @Test
  void mapsNotMappedToTransient() {
    assertThat(map(AttributeUse.of("NotMapped")).get(0).simpleName()).isEqualTo("Transient");
  }

  @Test
  void mapsRequiredToNotNull() {
    assertThat(map(AttributeUse.of("Required")).get(0).type().qualifiedName())
        .isEqualTo("jakarta.validation.constraints.NotNull");
  }

  /** StringLength is both a validation rule and a schema rule, so it produces both. */
  @Test
  void stringLengthProducesSizeAndColumnLength() {
    List<JavaAnnotation> annotations = map(AttributeUse.of("StringLength", "200"));

    assertThat(annotations).extracting(AttributeMapperTest::render)
        .containsExactly("@Size(max = 200)", "@Column(length = 200)");
  }

  @Test
  void stringLengthFoldsMinimumLengthIntoTheSameSize() {
    AttributeUse attribute =
        new AttributeUse("StringLength", List.of("200"), Map.of("MinimumLength", "1"));

    assertThat(render(map(attribute).get(0))).isEqualTo("@Size(min = 1, max = 200)");
  }

  @Test
  void mapsRangeToMinAndMax() {
    assertThat(map(AttributeUse.of("Range", "0", "10000")))
        .extracting(AttributeMapperTest::render)
        .containsExactly("@Min(0)", "@Max(10000)");
  }

  @Test
  void mapsEmailAndPattern() {
    assertThat(map(AttributeUse.of("EmailAddress")).get(0).simpleName()).isEqualTo("Email");
    assertThat(render(map(AttributeUse.of("RegularExpression", "\"^[0-9]+$\"")).get(0)))
        .isEqualTo("@Pattern(regexp = \"^[0-9]+$\")");
  }

  @Test
  void mapsJacksonAnnotations() {
    assertThat(render(map(AttributeUse.of("JsonPropertyName", "\"isbn13\"")).get(0)))
        .isEqualTo("@JsonProperty(\"isbn13\")");
    assertThat(map(AttributeUse.of("JsonIgnore")).get(0).simpleName()).isEqualTo("JsonIgnore");
  }

  @Test
  void dropsAttributesWithNoJavaCounterpart() {
    assertThat(mapper.map(AttributeUse.of("ApiController"))).isEmpty();
    assertThat(mapper.map(AttributeUse.of("SomethingBespoke"))).isEmpty();
  }

  /**
   * The case that forced merge to exist: [Column("title")] and [StringLength(200)] on the same
   * property both want to be @Column, and emitting it twice does not compile.
   */
  @Test
  void mergesColumnAnnotationsFromDifferentAttributes() {
    List<AttributeUse> attributes =
        List.of(
            AttributeUse.of("Required"),
            new AttributeUse("StringLength", List.of("200"), Map.of("MinimumLength", "1")),
            AttributeUse.of("Column", "\"title\""));

    List<JavaAnnotation> merged = mapper.mapAll(attributes).annotations();

    assertThat(merged).extracting(AttributeMapperTest::render)
        .containsExactly(
            "@NotNull",
            "@Size(min = 1, max = 200)",
            "@Column(name = \"title\", length = 200)");
  }

  @Test
  void snakeCasesPascalNames() {
    assertThat(AttributeMapper.snakeCase("AuthorId")).isEqualTo("author_id");
    assertThat(AttributeMapper.snakeCase("Id")).isEqualTo("id");
    assertThat(AttributeMapper.snakeCase("PublishedOn")).isEqualTo("published_on");
  }
}
