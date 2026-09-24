package com.portway.core.verify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JavaSourceIndexTest {

  private static final String SOURCE =
      """
      package a;

      /** A class. */
      public class Books {
        private final String name = "x(y) { z }";

        public Books() {
          call(1);
        }

        /**
         * Javadoc spanning
         * several lines, with braces { } and parens ( ).
         */
        @Override
        public java.util.List<String> all(
            @RequestParam(name = "size", defaultValue = "20") int size, String q) {
          var x = helper(size);
          return x;
        }

        private static int helper(int n) {
          return n + "}".length();
        }
      }
      """;

  @Test
  void findsMethodSpansIncludingAnnotationsAndIgnoringLiterals() {
    JavaSourceIndex index = JavaSourceIndex.of(SOURCE);

    assertThat(index.spans()).extracting(JavaSourceIndex.Span::name).containsExactly("all", "helper");
    JavaSourceIndex.Span all = index.spans().get(0);
    assertThat(all.paramCount()).isEqualTo(2);
    assertThat(all.startLine()).isEqualTo(15);
    assertThat(all.endLine()).isEqualTo(20);
    assertThat(index.methodAt(18)).get().extracting(JavaSourceIndex.Span::name).isEqualTo("all");
    assertThat(index.methodAt(23)).get().extracting(JavaSourceIndex.Span::name).isEqualTo("helper");
    // The constructor and field are not method bodies a report could own.
    assertThat(index.methodAt(8)).isEmpty();
    assertThat(index.methodAt(5)).isEmpty();
  }
}
