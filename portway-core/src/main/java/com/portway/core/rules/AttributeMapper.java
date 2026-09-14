package com.portway.core.rules;

import com.portway.core.ir.AttributeUse;
import com.portway.core.report.FindingCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps C# attributes to Java annotations.
 *
 * <p>One attribute does not always produce one annotation. {@code [StringLength(200)]} carries both
 * a validation constraint and a schema constraint, so it produces {@code @Size(max = 200)} and
 * {@code @Column(length = 200)}. Several attributes can also land on the same annotation, which is
 * why {@link #merge} exists: {@code [Column("title")]} and {@code [StringLength(200)]} together
 * must yield one {@code @Column(name = "title", length = 200)}, not two.
 */
public class AttributeMapper {

  private static final String JAKARTA_PERSISTENCE = "jakarta.persistence.";
  private static final String JAKARTA_VALIDATION = "jakarta.validation.constraints.";

  /** The result of mapping one attribute: annotations to emit, plus anything lossy. */
  public record Mapped(List<JavaAnnotation> annotations, List<FindingCode> notes) {
    public Mapped {
      annotations = List.copyOf(annotations == null ? List.of() : annotations);
      notes = List.copyOf(notes == null ? List.of() : notes);
    }

    static Mapped of(JavaAnnotation... annotations) {
      return new Mapped(List.of(annotations), List.of());
    }

    static Mapped withNote(FindingCode note, JavaAnnotation... annotations) {
      return new Mapped(List.of(annotations), List.of(note));
    }

    static final Mapped NOTHING = new Mapped(List.of(), List.of());
  }

  /**
   * Maps one attribute.
   *
   * @return empty when the attribute has no Java counterpart and should simply be dropped
   */
  public Optional<Mapped> map(AttributeUse attribute) {
    String first = attribute.firstArgUnquoted().orElse(null);

    return Optional.ofNullable(
        switch (attribute.name()) {
          // ---- JPA mapping ----
          case "Key" -> Mapped.of(JavaAnnotation.marker(JAKARTA_PERSISTENCE + "Id"));
          case "DatabaseGenerated" -> generatedValue(attribute);
          case "Table" -> first == null
              ? null
              : Mapped.of(JavaAnnotation.of(JAKARTA_PERSISTENCE + "Table", "name", quote(first)));
          case "Column" -> column(attribute, first);
          case "ForeignKey" -> first == null
              ? null
              : Mapped.of(
                  JavaAnnotation.of(
                      JAKARTA_PERSISTENCE + "JoinColumn", "name", quote(snakeCase(first))));
          case "NotMapped" -> Mapped.of(JavaAnnotation.marker(JAKARTA_PERSISTENCE + "Transient"));

          // ---- Bean validation ----
          case "Required" -> Mapped.of(JavaAnnotation.marker(JAKARTA_VALIDATION + "NotNull"));
          case "StringLength", "MaxLength" -> stringLength(attribute, first);
          case "MinLength" -> first == null
              ? null
              : Mapped.of(JavaAnnotation.of(JAKARTA_VALIDATION + "Size", "min", first));
          case "Range" -> range(attribute);
          case "EmailAddress" -> Mapped.of(JavaAnnotation.marker(JAKARTA_VALIDATION + "Email"));
          case "RegularExpression" -> first == null
              ? null
              : Mapped.of(
                  JavaAnnotation.of(JAKARTA_VALIDATION + "Pattern", "regexp", quote(first)));

          // ---- Serialisation ----
          case "JsonPropertyName" -> first == null
              ? null
              : Mapped.of(
                  JavaAnnotation.value(
                      "com.fasterxml.jackson.annotation.JsonProperty", quote(first)));
          case "JsonIgnore" -> Mapped.of(
              JavaAnnotation.marker("com.fasterxml.jackson.annotation.JsonIgnore"));

          // ---- Misc ----
          case "Obsolete" -> Mapped.of(JavaAnnotation.marker("java.lang.Deprecated"));
          // No Java equivalent, and silently dropping it would hide a real difference.
          case "Serializable" -> Mapped.withNote(FindingCode.UNSUPPORTED_CONSTRUCT);

          default -> null;
        });
  }

  /**
   * Collapses annotations of the same type into one.
   *
   * <p>Without this, an entity property carrying both {@code [Column("title")]} and {@code
   * [StringLength(200)]} emits {@code @Column} twice, which does not compile.
   */
  public List<JavaAnnotation> merge(List<JavaAnnotation> annotations) {
    Map<String, JavaAnnotation> byType = new LinkedHashMap<>();
    for (JavaAnnotation annotation : annotations) {
      byType.merge(annotation.type().qualifiedName(), annotation, JavaAnnotation::mergedWith);
    }
    return byType.values().stream().map(AttributeMapper::withCanonicalMemberOrder).toList();
  }

  /**
   * Member order within an annotation, so merged results read the way a Java developer would have
   * written them rather than in whatever order the C# attributes happened to appear.
   */
  private static final List<String> COLUMN_MEMBER_ORDER =
      List.of("name", "columnDefinition", "length", "precision", "scale", "nullable", "unique",
          "insertable", "updatable");

  private static JavaAnnotation withCanonicalMemberOrder(JavaAnnotation annotation) {
    if (!annotation.simpleName().equals("Column") || annotation.members().size() < 2) {
      return annotation;
    }
    Map<String, String> ordered = new LinkedHashMap<>();
    for (String member : COLUMN_MEMBER_ORDER) {
      if (annotation.members().containsKey(member)) {
        ordered.put(member, annotation.members().get(member));
      }
    }
    // Anything not in the canonical list keeps its original relative position, last.
    annotation.members().forEach(ordered::putIfAbsent);
    return new JavaAnnotation(annotation.type(), ordered);
  }

  /** Maps every attribute on a declaration, merging duplicates and gathering notes. */
  public Mapped mapAll(List<AttributeUse> attributes) {
    List<JavaAnnotation> annotations = new ArrayList<>();
    List<FindingCode> notes = new ArrayList<>();
    for (AttributeUse attribute : attributes) {
      map(attribute)
          .ifPresent(
              mapped -> {
                annotations.addAll(mapped.annotations());
                notes.addAll(mapped.notes());
              });
    }
    return new Mapped(merge(annotations), notes);
  }

  private Mapped generatedValue(AttributeUse attribute) {
    String arg = attribute.positionalArgs().isEmpty() ? "" : attribute.positionalArgs().get(0);
    // DatabaseGeneratedOption.Identity | .Computed | .None
    if (arg.endsWith("None")) {
      return Mapped.NOTHING;
    }
    return Mapped.of(
        new JavaAnnotation(
            JavaType.of(JAKARTA_PERSISTENCE + "GeneratedValue"),
            Map.of("strategy", "jakarta.persistence.GenerationType.IDENTITY")));
  }

  private Mapped column(AttributeUse attribute, String first) {
    Map<String, String> members = new LinkedHashMap<>();
    if (first != null && !first.contains("=")) {
      members.put("name", quote(first));
    }
    // [Column("price", TypeName = "numeric(10,2)")] carries the SQL type, which
    // JPA expresses as columnDefinition.
    String typeName = attribute.namedArgs().get("TypeName");
    if (typeName != null) {
      members.put("columnDefinition", typeName);
    }
    return members.isEmpty()
        ? Mapped.NOTHING
        : Mapped.of(new JavaAnnotation(JavaType.of(JAKARTA_PERSISTENCE + "Column"), members));
  }

  /**
   * {@code [StringLength(n)]} is both a validation rule and a schema rule, so it produces a
   * {@code @Size} and a {@code @Column(length)}. A MinimumLength named argument folds into the
   * same {@code @Size}.
   */
  private Mapped stringLength(AttributeUse attribute, String first) {
    if (first == null) {
      return null;
    }
    Map<String, String> size = new LinkedHashMap<>();
    String min = attribute.namedArgs().get("MinimumLength");
    if (min != null) {
      size.put("min", min);
    }
    size.put("max", first);

    return Mapped.of(
        new JavaAnnotation(JavaType.of(JAKARTA_VALIDATION + "Size"), size),
        JavaAnnotation.of(JAKARTA_PERSISTENCE + "Column", "length", first));
  }

  private Mapped range(AttributeUse attribute) {
    if (attribute.positionalArgs().size() < 2) {
      return null;
    }
    return Mapped.of(
        JavaAnnotation.value(JAKARTA_VALIDATION + "Min", attribute.positionalArgs().get(0)),
        JavaAnnotation.value(JAKARTA_VALIDATION + "Max", attribute.positionalArgs().get(1)));
  }

  private static String quote(String raw) {
    return "\"" + raw.replace("\"", "\\\"") + "\"";
  }

  /** {@code AuthorId} becomes {@code author_id}, which is the SQL convention Spring expects. */
  public static String snakeCase(String name) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isUpperCase(c)) {
        if (i > 0) {
          sb.append('_');
        }
        sb.append(Character.toLowerCase(c));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
