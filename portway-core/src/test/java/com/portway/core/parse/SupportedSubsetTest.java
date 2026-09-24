package com.portway.core.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.portway.core.ir.MethodDecl;
import com.portway.core.ir.ParamDecl;
import com.portway.core.ir.PropertyDecl;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.TypeDecl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * The boundary of the supported C# subset.
 *
 * <p>Two rules are asserted here. A construct outside the subset is rejected with its name and
 * line. A construct inside it survives into the IR without losing information. The failure mode
 * both guard against is the same: a file that parses "successfully" into an IR that means something
 * different from the source.
 */
class SupportedSubsetTest {

  private final CSharpSourceParser parser = new CSharpSourceParser();

  private SourceFile ir(String source) {
    ParseResult result = parser.parse(source, "Probe.cs");
    assertThat(result.errors()).describedAs("errors").isEmpty();
    return new CSharpToIrVisitor(result.tokens()).toSourceFile(result);
  }

  private MethodDecl onlyMethod(String source) {
    return ir(source).types().get(0).methods().get(0);
  }

  // ------------------------------------------------------------- rejected

  @ParameterizedTest(name = "{1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "public unsafe class C { }                                            | unsafe code",
        "public class C { public unsafe int* P; }                             | pointer type",
        "public class C { void M() { var q = from x in xs select x; } }       | LINQ query syntax",
        "public class Outer { public class Inner { public int Id {get;set;} } } | nested type declaration",
        "public class Outer { public enum Kind { A, B } }                     | nested type declaration",
        "public class C { public int this[int i] => i; }                      | indexer",
        "public class C { public static C operator +(C a, C b) => a; }        | operator overload",
        "public class C { ~C() { } }                                          | finalizer",
        "public class C { public event System.EventHandler Changed; }         | event declaration",
        "public class C { void M() { System.Span<int> s = stackalloc int[3]; } } | stackalloc",
      })
  void rejectsUnsupportedConstructWithItsName(String source, String construct) {
    ParseResult result = parser.parse(source, "Probe.cs");

    assertThat(result.ok()).isFalse();
    assertThat(result.errors())
        .anySatisfy(
            e -> {
              assertThat(e.message()).contains("Unsupported construct").contains(construct);
              assertThat(e.line()).isEqualTo(1);
              assertThat(e.path()).isEqualTo("Probe.cs");
            });
  }

  @Test
  void rejectsTypesSpreadAcrossSiblingNamespaces() {
    ParseResult result =
        parser.parse("namespace A { class X {} }\nnamespace B { class Y {} }", "Probe.cs");

    assertThat(result.ok()).isFalse();
    assertThat(result.errors().get(0).message()).contains("more than one namespace").contains("A, B");
    assertThat(result.errors().get(0).line()).isEqualTo(2);
  }

  @Test
  void acceptsNestedNamespacesHoldingTypesInOnlyOne() {
    SourceFile file = ir("namespace A { namespace B { class X {} } }");

    assertThat(file.namespaceName()).isEqualTo("A.B");
  }

  // -------------------------------------------------------------- preserved

  @Test
  void keepsParamsArrayAsVarargsParameter() {
    MethodDecl method = onlyMethod("class C { public void M(int count, params string[] values) {} }");

    assertThat(method.params()).extracting(ParamDecl::name).containsExactly("count", "values");
    ParamDecl values = method.params().get(1);
    assertThat(values.isParamsArray()).isTrue();
    assertThat(values.type().name()).isEqualTo("string");
    assertThat(values.type().arrayRank()).isEqualTo(1);
  }

  @Test
  void keepsByReferenceModifiers() {
    MethodDecl method =
        onlyMethod("class C { public bool TryGet(int key, out string value) { value = \"\"; return true; } }");

    assertThat(method.params().get(0).modifier()).isNull();
    assertThat(method.params().get(1).modifier()).isEqualTo("out");
    assertThat(method.params().get(1).isByReference()).isTrue();
  }

  @Test
  void keepsExpressionBodyExactlyAsWritten() {
    MethodDecl voidMethod = onlyMethod("class C { void M() => System.Console.WriteLine(1); }");
    MethodDecl throwing = onlyMethod("class C { int M() => throw new System.Exception(); }");

    // No invented `return`: whether one belongs depends on the return type, and
    // `return throw ...` is not valid in either language.
    assertThat(voidMethod.bodyRaw()).isEqualTo("=> System.Console.WriteLine(1)");
    assertThat(voidMethod.isExpressionBodied()).isTrue();
    assertThat(voidMethod.expressionBody()).isEqualTo("System.Console.WriteLine(1)");
    assertThat(throwing.expressionBody()).isEqualTo("throw new System.Exception()");
  }

  @Test
  void keepsAccessorBodies() {
    TypeDecl type =
        ir("""
            class C {
              private int _p;
              public int Computed { get { return 42; } }
              public int Guarded { get => _p; set { if (value < 0) throw new System.Exception(); _p = value; } }
              public string Title { get; } = "x";
            }
            """)
            .types()
            .get(0);

    PropertyDecl computed = property(type, "Computed");
    assertThat(computed.getterBody()).isEqualTo("{ return 42; }");
    assertThat(computed.isComputed()).isTrue();

    PropertyDecl guarded = property(type, "Guarded");
    assertThat(guarded.getterBody()).isEqualTo("=> _p");
    assertThat(guarded.setterBody()).contains("if (value < 0)");
    assertThat(guarded.isComputed()).isFalse();
    assertThat(guarded.hasAccessorLogic()).isTrue();

    // A get-only auto-property with an initializer is stored, not computed.
    PropertyDecl title = property(type, "Title");
    assertThat(title.initializer()).isEqualTo("\"x\"");
    assertThat(title.isComputed()).isFalse();
    assertThat(title.hasAccessorLogic()).isFalse();
  }

  @Test
  void keepsUsingsDeclaredInsideBlockNamespace() {
    SourceFile file = ir("using System;\nnamespace A { using System.Collections.Generic; class X {} }");

    assertThat(file.usings()).containsExactly("System", "System.Collections.Generic");
  }

  private static PropertyDecl property(TypeDecl type, String name) {
    return type.properties().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
  }
}
