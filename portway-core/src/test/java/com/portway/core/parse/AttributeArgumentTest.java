package com.portway.core.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.portway.core.ir.AttributeUse;
import com.portway.core.ir.SourceFile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Attribute arguments are the one place the grammar shape and the C# surface syntax disagree, so
 * the splitting is pinned down separately from the sample project.
 */
class AttributeArgumentTest {

  private List<AttributeUse> attributesOn(String attributeSource) {
    String src =
        """
        namespace T;
        public class Thing
        {
            %s
            public string Name { get; set; }
        }
        """
            .formatted(attributeSource);
    ParseResult result = new CSharpSourceParser().parse(src, "T.cs");
    assertThat(result.errors()).isEmpty();
    SourceFile file = new CSharpToIrVisitor(result.tokens()).toSourceFile(result);
    return file.types().get(0).properties().get(0).attributes();
  }

  private AttributeUse only(String attributeSource) {
    List<AttributeUse> attrs = attributesOn(attributeSource);
    assertThat(attrs).hasSize(1);
    return attrs.get(0);
  }

  @Test
  void splitsNamedArgumentsFromPositional() {
    AttributeUse a = only("[StringLength(200, MinimumLength = 1)]");
    assertThat(a.positionalArgs()).containsExactly("200");
    assertThat(a.namedArgs()).containsExactly(java.util.Map.entry("MinimumLength", "1"));
  }

  @Test
  void keepsAllPositionalArgumentsPositional() {
    AttributeUse a = only("[Range(0, 10000)]");
    assertThat(a.positionalArgs()).containsExactly("0", "10000");
    assertThat(a.namedArgs()).isEmpty();
  }

  @Test
  void doesNotSplitOnComparisonOperators() {
    AttributeUse a = only("[Check(\"price >= 0\")]");
    assertThat(a.positionalArgs()).containsExactly("\"price >= 0\"");
    assertThat(a.namedArgs()).isEmpty();
  }

  @Test
  void doesNotSplitOnEqualsInsideAStringLiteral() {
    AttributeUse a = only("[Display(Name = \"a = b\")]");
    assertThat(a.positionalArgs()).isEmpty();
    assertThat(a.namedArgs()).containsExactly(java.util.Map.entry("Name", "\"a = b\""));
  }

  @Test
  void doesNotSplitALambdaArrowIntoANamedArgument() {
    AttributeUse a = only("[Index(x => x.Name)]");
    assertThat(a.namedArgs())
        .describedAs("=> is a lambda arrow, not an assignment")
        .isEmpty();
    assertThat(a.positionalArgs()).containsExactly("x => x.Name");
  }

  @Test
  void stripsTheAttributeSuffix() {
    assertThat(only("[RequiredAttribute]").name()).isEqualTo("Required");
    assertThat(only("[Required]").name()).isEqualTo("Required");
  }

  @Test
  void handlesMultipleAttributesInOneSection() {
    List<AttributeUse> attrs = attributesOn("[Required, StringLength(10)]");
    assertThat(attrs).extracting(AttributeUse::name).containsExactly("Required", "StringLength");
  }
}
