package com.portway.core.generate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JavadocTest {

  @Test
  void stripsSummaryTags() {
    String xml = """
        <summary>
        A book held in the store's catalogue.
        </summary>""";

    assertThat(Javadoc.fromXmlDoc(xml)).isEqualTo("A book held in the store's catalogue.");
  }

  @Test
  void keepsMultipleSummaryLines() {
    String xml = """
        <summary>
        First line.
        Second line.
        </summary>""";

    assertThat(Javadoc.fromXmlDoc(xml)).isEqualTo("First line.\nSecond line.");
  }

  @Test
  void convertsParamAndReturns() {
    String xml = """
        <summary>Finds a book.</summary>
        <param name="id">the identifier</param>
        <returns>the book, or null</returns>""";

    assertThat(Javadoc.fromXmlDoc(xml))
        .isEqualTo("Finds a book.\n@param id the identifier\n@return the book, or null");
  }

  @Test
  void returnsNullForNothingUseful() {
    assertThat(Javadoc.fromXmlDoc(null)).isNull();
    assertThat(Javadoc.fromXmlDoc("   ")).isNull();
    assertThat(Javadoc.fromXmlDoc("<summary></summary>")).isNull();
  }
}
