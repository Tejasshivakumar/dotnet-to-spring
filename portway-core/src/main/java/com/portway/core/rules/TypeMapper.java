package com.portway.core.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.portway.core.ir.TypeRef;
import com.portway.core.report.FindingCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns C# types into Java types using the table in {@code type-mappings.yaml}.
 *
 * <p>The table is data, not code, for three reasons: it can be reviewed and extended without a
 * recompile, it can be rendered into the LLM system prompt so the model and the rule engine can
 * never disagree, and it makes the lossy mappings visible in one place rather than scattered
 * through a switch statement.
 *
 * <p>Anything not in the table is assumed to be a type from the project being migrated and is
 * passed through by name. That is deliberate: {@code BookDto} should map to {@code BookDto}.
 */
public class TypeMapper {

  private static final String DEFAULT_RESOURCE = "/type-mappings.yaml";
  /** Marker in the generics table for Nullable&lt;T&gt;, which collapses to a boxed T. */
  private static final String BOXED_ARGUMENT = "BOXED_ARGUMENT";

  private final Map<String, ScalarMapping> scalars;
  private final Map<String, String> generics;
  private final Map<String, UnwrapMapping> unwrap;
  private final Map<String, ResponseMapping> responses;

  record ScalarMapping(String java, String boxed, FindingCode finding) {}

  record UnwrapMapping(String empty, FindingCode finding) {}

  record ResponseMapping(String java, boolean wildcard) {}

  TypeMapper(
      Map<String, ScalarMapping> scalars,
      Map<String, String> generics,
      Map<String, UnwrapMapping> unwrap,
      Map<String, ResponseMapping> responses) {
    this.scalars = Map.copyOf(scalars);
    this.generics = Map.copyOf(generics);
    this.unwrap = Map.copyOf(unwrap);
    this.responses = Map.copyOf(responses);
  }

  /** Loads the table shipped with the library. */
  public static TypeMapper fromDefaults() {
    try (InputStream in = TypeMapper.class.getResourceAsStream(DEFAULT_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + DEFAULT_RESOURCE);
      }
      return fromYaml(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + DEFAULT_RESOURCE, e);
    }
  }

  @SuppressWarnings("unchecked")
  public static TypeMapper fromYaml(InputStream yaml) {
    try {
      Map<String, Object> root =
          new ObjectMapper(new YAMLFactory()).readValue(yaml, Map.class);

      Map<String, ScalarMapping> scalars = new LinkedHashMap<>();
      Map<String, Object> rawScalars =
          (Map<String, Object>) root.getOrDefault("scalars", Map.of());
      rawScalars.forEach(
          (name, value) -> {
            Map<String, Object> entry = (Map<String, Object>) value;
            scalars.put(
                name,
                new ScalarMapping(
                    (String) entry.get("java"),
                    (String) entry.get("boxed"),
                    findingCode(entry.get("finding"))));
          });

      Map<String, String> generics =
          new LinkedHashMap<>((Map<String, String>) root.getOrDefault("generics", Map.of()));

      Map<String, UnwrapMapping> unwrap = new LinkedHashMap<>();
      Map<String, Object> rawUnwrap = (Map<String, Object>) root.getOrDefault("unwrap", Map.of());
      rawUnwrap.forEach(
          (name, value) -> {
            Map<String, Object> entry = (Map<String, Object>) value;
            unwrap.put(
                name,
                new UnwrapMapping(
                    (String) entry.getOrDefault("empty", "void"),
                    findingCode(entry.get("finding"))));
          });

      Map<String, ResponseMapping> responses = new LinkedHashMap<>();
      Map<String, Object> rawResponses =
          (Map<String, Object>) root.getOrDefault("responses", Map.of());
      rawResponses.forEach(
          (name, value) -> {
            Map<String, Object> entry = (Map<String, Object>) value;
            responses.put(
                name,
                new ResponseMapping(
                    (String) entry.get("java"),
                    Boolean.TRUE.equals(entry.get("wildcard"))));
          });

      return new TypeMapper(scalars, generics, unwrap, responses);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot parse type mappings", e);
    }
  }

  private static FindingCode findingCode(Object raw) {
    return raw == null ? null : FindingCode.valueOf(raw.toString());
  }

  /** Maps a type as it appears in a field, property or parameter position. */
  public MappedType map(TypeRef csharp) {
    return map(csharp, false);
  }

  /**
   * Maps a type.
   *
   * @param forceBoxed true in positions where a primitive is illegal, such as a generic argument
   */
  public MappedType map(TypeRef csharp, boolean forceBoxed) {
    List<FindingCode> notes = new ArrayList<>();
    JavaType type = mapInternal(csharp, forceBoxed, notes);
    return new MappedType(type, notes);
  }

  private JavaType mapInternal(TypeRef csharp, boolean forceBoxed, List<FindingCode> notes) {
    if (csharp == null) {
      return JavaType.OBJECT;
    }

    // Arrays: map the element type, then re-apply the rank.
    if (csharp.isArray()) {
      TypeRef element = new TypeRef(csharp.name(), csharp.typeArgs(), csharp.nullable(), 0);
      return mapInternal(element, false, notes).withArrayDimensions(csharp.arrayRank());
    }

    // Task<T> and ValueTask<T> collapse to T, dropping asynchrony entirely.
    UnwrapMapping unwrapping = unwrap.get(csharp.name());
    if (unwrapping != null) {
      if (unwrapping.finding() != null) {
        notes.add(unwrapping.finding());
      }
      if (csharp.typeArgs().isEmpty()) {
        // Bare Task: nothing to unwrap to, so the method simply returns nothing.
        return "void".equals(unwrapping.empty())
            ? JavaType.VOID
            : JavaType.of(unwrapping.empty());
      }
      return mapInternal(csharp.typeArgs().get(0), forceBoxed, notes);
    }

    // IActionResult / ActionResult<T> become ResponseEntity.
    ResponseMapping response = responses.get(csharp.name());
    if (response != null) {
      JavaType base = JavaType.of(response.java());
      if (response.wildcard() || csharp.typeArgs().isEmpty()) {
        return base.asWildcard();
      }
      return base.withTypeArgs(List.of(mapInternal(csharp.typeArgs().get(0), true, notes)));
    }

    // Generic containers, with arguments mapped recursively and always boxed:
    // Java has no List<int>.
    String genericTarget = generics.get(csharp.name());
    if (genericTarget != null) {
      if (BOXED_ARGUMENT.equals(genericTarget)) {
        return csharp.typeArgs().isEmpty()
            ? JavaType.OBJECT
            : mapInternal(csharp.typeArgs().get(0), true, notes);
      }
      List<JavaType> args = new ArrayList<>();
      for (TypeRef arg : csharp.typeArgs()) {
        args.add(mapInternal(arg, true, notes));
      }
      return JavaType.of(genericTarget).withTypeArgs(args);
    }

    ScalarMapping scalar = scalars.get(csharp.name());
    if (scalar != null) {
      if (scalar.finding() != null) {
        notes.add(scalar.finding());
      }
      boolean wantBoxed = forceBoxed || csharp.nullable();
      if (wantBoxed && scalar.boxed() != null) {
        return JavaType.of(scalar.boxed());
      }
      JavaType mapped = JavaType.of(scalar.java());
      // A name with no package and no boxed form, such as int or void, is a primitive.
      return isPrimitiveName(scalar.java()) ? JavaType.primitive(scalar.java()) : mapped;
    }

    // Unknown: a type from the project being migrated. Passed through by name,
    // with its generic arguments mapped, so BookDto stays BookDto.
    if (csharp.nullable()) {
      notes.add(FindingCode.NULLABLE_REFERENCE);
    }
    List<JavaType> args = new ArrayList<>();
    for (TypeRef arg : csharp.typeArgs()) {
      args.add(mapInternal(arg, true, notes));
    }
    return new JavaType("", csharp.name(), args, false, 0, false);
  }

  private static boolean isPrimitiveName(String name) {
    return switch (name) {
      case "int", "long", "short", "byte", "boolean", "char", "double", "float", "void" -> true;
      default -> false;
    };
  }

  /**
   * The table rendered as text for the LLM system prompt, so the model is told exactly the same
   * rules the engine applies.
   */
  public String asPromptTable() {
    StringBuilder sb = new StringBuilder("C# type -> Java type\n");
    scalars.forEach((name, m) -> sb.append("  ").append(name).append(" -> ")
        .append(m.java()).append('\n'));
    generics.forEach((name, target) -> sb.append("  ").append(name).append("<T> -> ")
        .append(target).append("<T>\n"));
    unwrap.forEach((name, m) -> sb.append("  ").append(name)
        .append("<T> -> T (drop await)\n"));
    return sb.toString();
  }
}
