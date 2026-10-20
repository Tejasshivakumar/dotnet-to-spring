package com.portway.app.verify;

import com.portway.core.generate.PomGenerator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Only the generated, known build may run in the networked dependency resolver. */
final class VerificationPom {
  private VerificationPom() {}

  static void validate(Path pom) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      Element root = factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
      if (!root.getTagName().equals("project")) reject();
      Map<String, Element> top = uniqueChildren(root);
      if (!List.of("modelVersion", "parent", "groupId", "artifactId", "version", "name",
          "description", "properties", "dependencies", "build", "packaging", "url").containsAll(top.keySet())) reject();
      if (top.containsKey("packaging") && !text(top.get("packaging")).equals("jar")) reject();
      Map<String, Element> parent = uniqueChildren(top.get("parent"));
      if (!parent.keySet().equals(java.util.Set.of("groupId", "artifactId", "version", "relativePath"))
          || !text(parent.get("groupId")).equals("org.springframework.boot")
          || !text(parent.get("artifactId")).equals("spring-boot-starter-parent")
          || !text(parent.get("version")).equals(PomGenerator.SPRING_BOOT_VERSION)
          || !text(parent.get("relativePath")).isEmpty()) reject();
      Map<String, Element> build = uniqueChildren(top.get("build"));
      if (!build.keySet().equals(java.util.Set.of("plugins"))) reject();
      List<Element> plugins = children(build.get("plugins"));
      if (plugins.size() != 1 || !plugins.getFirst().getTagName().equals("plugin")) reject();
      Map<String, Element> plugin = uniqueChildren(plugins.getFirst());
      if (!plugin.keySet().equals(java.util.Set.of("groupId", "artifactId"))
          || !text(plugin.get("groupId")).equals("org.springframework.boot")
          || !text(plugin.get("artifactId")).equals("spring-boot-maven-plugin")) reject();
    } catch (Exception e) {
      throw new IllegalArgumentException("Docker verification requires the generated Spring Boot parent and build configuration. "
          + "Custom parents, plugins, profiles, modules and repositories must be built separately in a trusted environment.", e);
    }
  }

  private static Map<String, Element> uniqueChildren(Element parent) {
    Map<String, Element> result = new java.util.LinkedHashMap<>();
    for (Element element : children(parent)) {
      if (result.put(element.getTagName(), element) != null) reject();
    }
    return result;
  }

  private static List<Element> children(Element parent) {
    if (parent == null) throw new IllegalArgumentException("Missing required element");
    List<Element> elements = new ArrayList<>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element) elements.add(element);
    }
    return elements;
  }

  private static String text(Element element) {
    if (element == null || !children(element).isEmpty()) throw new IllegalArgumentException("Expected text");
    return element.getTextContent().trim();
  }

  private static void reject() {
    throw new IllegalArgumentException("Unsupported build configuration");
  }
}
