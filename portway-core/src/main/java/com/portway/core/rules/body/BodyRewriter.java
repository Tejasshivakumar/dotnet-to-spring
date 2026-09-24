package com.portway.core.rules.body;

import static com.portway.core.rules.body.JavaImports.ref;

import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;
import com.portway.core.generate.Names;
import com.portway.core.report.FindingCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tier A: rewrites a C# method body into Java with an ordered list of token-level rules.
 *
 * <p>This is deliberately not a compiler. Each rule recognises one idiom it can translate exactly
 * and leaves everything else alone. After the rules run, a residual scan looks for any C#-specific
 * token that survived. A body qualifies for tier A only when nothing survived <em>and</em> the
 * result parses as Java; otherwise it goes to the LLM (tier B) or, for constructs that have no
 * honest translation at all, to a human (tier C).
 *
 * <p>Rules run over text with every string literal and comment masked out, so no rule can rewrite
 * the inside of a literal. Rule order matters and is part of the design: for example the
 * null-conditional rule emits {@code v -> v.Name}, which the later property rule turns into {@code
 * v -> v.getName()}.
 */
public class BodyRewriter {

  private static final String RESPONSE_ENTITY = ref("org.springframework.http.ResponseEntity");
  private static final String HTTP_STATUS = ref("org.springframework.http.HttpStatus");
  private static final String COMPARATOR = ref("java.util.Comparator");
  private static final String OPTIONAL = ref("java.util.Optional");
  private static final String OBJECTS = ref("java.util.Objects");

  /** One named, independently testable rewrite. */
  record Rule(String name, java.util.function.UnaryOperator<Work> apply) {}

  /** Raised by a rule or check that finds something it cannot translate. */
  static final class Bail extends RuntimeException {
    final Tier tier;
    final FindingCode code;

    Bail(Tier tier, String reason, FindingCode code) {
      super(reason, null, false, false);
      this.tier = tier;
      this.code = code;
    }
  }

  /** Mutable state threaded through the rules. */
  static final class Work {
    String text;
    final List<String> literals;
    final RewriteContext ctx;
    final List<FindingCode> notes = new ArrayList<>();
    final Set<String> repositories = new LinkedHashSet<>();

    Work(CSharpText.Masked masked, RewriteContext ctx) {
      this.text = masked.text();
      this.literals = new ArrayList<>(masked.literals());
      this.ctx = ctx;
    }

    /** Adds a Java literal to the masked set and returns its placeholder. */
    String literal(String javaLiteral) {
      String placeholder = "\u0002" + literals.size() + "\u0003";
      literals.add(javaLiteral);
      return placeholder;
    }

    String restore() {
      return new CSharpText.Masked(text, literals).restore();
    }

    void note(FindingCode code) {
      if (!notes.contains(code)) {
        notes.add(code);
      }
    }
  }

  private final List<Rule> rules =
      List.of(
          new Rule("soft-deny", this::softDeny),
          new Rule("await", this::await),
          new Rule("ef-query-operators", this::efQueryOperators),
          new Rule("dbset-to-repository", this::dbSetToRepository),
          new Rule("object-initializer", this::objectInitializer),
          new Rule("controller-results", this::controllerResults),
          new Rule("logging", this::logging),
          new Rule("implicit-ok", this::implicitOk),
          new Rule("null-checks", this::nullChecks),
          new Rule("linq-chains", this::linqChains),
          new Rule("lambdas", w -> replace(w, "=>", "->")),
          new Rule("foreach", this::foreach),
          new Rule("framework-statics", this::frameworkStatics),
          new Rule("exceptions", this::exceptions),
          new Rule("local-types", this::localTypes),
          new Rule("collection-size", this::collectionSize),
          new Rule("property-setters", this::propertySetters),
          new Rule("property-getters", this::propertyGetters),
          new Rule("field-renames", this::fieldRenames),
          new Rule("method-names", this::methodNames),
          new Rule("string-equality", this::stringEquality));

  private final Formatter formatter = new Formatter();

  /**
   * Rewrites one body.
   *
   * @param bodyRaw the IR's exact C# body: a block, or {@code => expr} for an expression body
   * @param byReferenceParameters true when the method takes {@code ref}, {@code out} or {@code in}
   *     parameters, which Java cannot express
   */
  public RewriteResult rewrite(String bodyRaw, RewriteContext ctx, boolean byReferenceParameters) {
    List<FindingCode> notes = new ArrayList<>();
    try {
      if (byReferenceParameters) {
        throw new Bail(
            Tier.C,
            "takes a ref, out or in parameter; Java passes everything by value",
            FindingCode.UNSUPPORTED_CONSTRUCT);
      }
      String block = asBlock(bodyRaw, ctx.returnsVoid());
      hardDeny(block);
      String java = interpolatedStrings(block);
      Work work = new Work(CSharpText.mask(java), ctx);
      for (Rule rule : rules) {
        work = rule.apply().apply(work);
      }
      residual(work);
      notes.addAll(work.notes);
      String restored = work.restore();
      String statements = CSharpText.unwrapBlock(restored);
      checkSyntax(statements);
      return new RewriteResult(Tier.A, statements, null, notes, work.repositories);
    } catch (Bail bail) {
      if (bail.code != null) {
        notes.add(bail.code);
      }
      return RewriteResult.bail(bail.tier, bail.getMessage(), notes);
    }
  }

  /** Runs the rules over a fragment without the final checks. Exposed for rule-level tests. */
  String applyRules(String csharp, RewriteContext ctx) {
    Work work = new Work(CSharpText.mask(interpolatedStrings(csharp)), ctx);
    for (Rule rule : rules) {
      if (!rule.name().equals("soft-deny")) {
        work = rule.apply().apply(work);
      }
    }
    return JavaImports.toSimpleNames(work.restore());
  }

  // ------------------------------------------------------------ framing

  /**
   * An expression body becomes a block. Whether it becomes {@code return x;} or {@code x;} depends
   * on the return type, which is why the parser keeps the arrow form and leaves this decision here.
   */
  static String asBlock(String bodyRaw, boolean returnsVoid) {
    String body = bodyRaw.strip();
    if (!body.startsWith("=>")) {
      return body;
    }
    String expression = body.substring(2).strip();
    if (returnsVoid || expression.startsWith("throw ")) {
      return "{\n    " + expression + ";\n}";
    }
    return "{\n    return " + expression + ";\n}";
  }

  /**
   * Constructs that never go to the model. They have no Java equivalent a translation could
   * produce without changing what the method means, so the honest output is a stub.
   */
  private static final Map<Pattern, String> HARD_DENY = new LinkedHashMap<>();

  static {
    HARD_DENY.put(
        Pattern.compile("\\byield\\s+(return|break)\\b"),
        "uses yield return, which has no direct Java equivalent");
    HARD_DENY.put(Pattern.compile("\\bunsafe\\b"), "uses unsafe code");
    HARD_DENY.put(Pattern.compile("\\bstackalloc\\b"), "uses stackalloc");
    HARD_DENY.put(Pattern.compile("\\bgoto\\b"), "uses goto");
  }

  private static void hardDeny(String block) {
    String code = CSharpText.mask(block).text();
    for (Map.Entry<Pattern, String> rule : HARD_DENY.entrySet()) {
      if (rule.getKey().matcher(code).find()) {
        throw new Bail(Tier.C, rule.getValue(), FindingCode.UNSUPPORTED_CONSTRUCT);
      }
    }
  }

  // --------------------------------------------------------- strings

  /**
   * {@code $"No author with id {id}"} becomes {@code "No author with id " + id}, and verbatim
   * {@code @"C:\x"} becomes an escaped Java literal. Runs before masking because the holes of an
   * interpolated string are code, not text.
   */
  static String interpolatedStrings(String source) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < source.length()) {
      char c = source.charAt(i);
      if (c == '/' && i + 1 < source.length() && (source.charAt(i + 1) == '/' || source.charAt(i + 1) == '*')) {
        int end =
            source.charAt(i + 1) == '/'
                ? indexOrEnd(source, "\n", i)
                : indexOrEnd(source, "*/", i + 2) + 2;
        end = Math.min(end, source.length());
        out.append(source, i, end);
        i = end;
      } else if (source.startsWith("$@\"", i) || source.startsWith("@$\"", i)) {
        throw new Bail(Tier.B, "uses a verbatim interpolated string", null);
      } else if (source.startsWith("$\"", i)) {
        int end = interpolationEnd(source, i + 2);
        out.append(interpolation(source.substring(i + 2, end)));
        i = end + 1;
      } else if (source.startsWith("@\"", i)) {
        int j = i + 2;
        StringBuilder content = new StringBuilder();
        while (j < source.length()) {
          if (source.charAt(j) == '"') {
            if (j + 1 < source.length() && source.charAt(j + 1) == '"') {
              content.append('"');
              j += 2;
              continue;
            }
            break;
          }
          content.append(source.charAt(j));
          j++;
        }
        out.append(javaStringLiteral(content.toString()));
        i = j + 1;
      } else if (c == '"' || c == '\'') {
        int end = skipQuoted(source, i);
        out.append(source, i, end);
        i = end;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  private static int indexOrEnd(String s, String needle, int from) {
    int i = s.indexOf(needle, from);
    return i < 0 ? s.length() : i;
  }

  private static int skipQuoted(String s, int start) {
    char quote = s.charAt(start);
    int i = start + 1;
    while (i < s.length() && s.charAt(i) != quote && s.charAt(i) != '\n') {
      i += s.charAt(i) == '\\' ? 2 : 1;
    }
    return Math.min(i + 1, s.length());
  }

  /** Index of the closing quote of an interpolated string whose content starts at {@code from}. */
  private static int interpolationEnd(String s, int from) {
    int depth = 0;
    for (int i = from; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && depth == 0) {
        i++;
      } else if (c == '{') {
        if (depth == 0 && i + 1 < s.length() && s.charAt(i + 1) == '{') {
          i++;
        } else {
          depth++;
        }
      } else if (c == '}') {
        if (depth == 0 && i + 1 < s.length() && s.charAt(i + 1) == '}') {
          i++;
        } else {
          depth--;
        }
      } else if (c == '"') {
        if (depth == 0) {
          return i;
        }
        throw new Bail(Tier.B, "uses a string literal inside an interpolation hole", null);
      }
    }
    throw new Bail(Tier.B, "has an unterminated interpolated string", null);
  }

  private static String interpolation(String content) {
    List<String> parts = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    boolean startsWithHole = false;
    int i = 0;
    while (i < content.length()) {
      char c = content.charAt(i);
      if (c == '{' && i + 1 < content.length() && content.charAt(i + 1) == '{') {
        text.append('{');
        i += 2;
      } else if (c == '}' && i + 1 < content.length() && content.charAt(i + 1) == '}') {
        text.append('}');
        i += 2;
      } else if (c == '{') {
        int close = CSharpText.matching(content, i);
        String hole = content.substring(i + 1, close).strip();
        if (!CSharpText.splitTopLevel(hole, ':').stream().limit(2).toList().equals(List.of(hole))
            || !CSharpText.splitTopLevel(hole, ',').stream().limit(2).toList().equals(List.of(hole))) {
          throw new Bail(Tier.B, "uses an interpolation format or alignment specifier", null);
        }
        if (!text.isEmpty()) {
          parts.add("\"" + text + "\"");
          text.setLength(0);
        } else if (parts.isEmpty()) {
          startsWithHole = true;
        }
        parts.add(hole.matches("[\\w.()]+") ? hole : "(" + hole + ")");
        i = close + 1;
      } else {
        text.append(c);
        i++;
      }
    }
    if (!text.isEmpty()) {
      parts.add("\"" + text + "\"");
    }
    if (parts.isEmpty()) {
      return "\"\"";
    }
    if (parts.size() == 1 && startsWithHole) {
      return "String.valueOf(" + parts.get(0) + ")";
    }
    // "" + a + b keeps two numeric holes from being added together before
    // concatenation. When text follows the first hole, the text already forces it.
    String joined = String.join(" + ", parts);
    boolean secondIsText = parts.size() > 1 && parts.get(1).startsWith("\"");
    return startsWithHole && !secondIsText ? "\"\" + " + joined : joined;
  }

  static String javaStringLiteral(String content) {
    return "\""
        + content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
        + "\"";
  }

  // ------------------------------------------------------------ rules

  private static final Map<Pattern, String> SOFT_DENY = new LinkedHashMap<>();

  static {
    SOFT_DENY.put(Pattern.compile("\\btypeof\\b"), "uses typeof");
    SOFT_DENY.put(Pattern.compile("\\bdynamic\\b"), "uses dynamic");
    SOFT_DENY.put(Pattern.compile("\\block\\s*\\("), "uses a lock statement");
    SOFT_DENY.put(Pattern.compile("\\busing\\s*(\\(|var\\b)"), "uses a using statement for disposal");
    SOFT_DENY.put(Pattern.compile("\\bdefault\\s*[(;,)]"), "uses a default literal");
    SOFT_DENY.put(Pattern.compile("\\bchecked\\b"), "uses checked arithmetic");
    SOFT_DENY.put(Pattern.compile("\\?\\["), "uses null-conditional indexing");
    SOFT_DENY.put(Pattern.compile("\\?\\?="), "uses null-coalescing assignment");
    SOFT_DENY.put(Pattern.compile("\\bswitch\\s*\\{"), "uses a switch expression");
    SOFT_DENY.put(Pattern.compile("\\bwith\\s*\\{"), "uses a with expression");
    SOFT_DENY.put(Pattern.compile("\\bis\\s+(?!null\\b|not\\s+null\\b)"), "uses pattern matching");
    SOFT_DENY.put(Pattern.compile("\\bas\\s+[A-Z]"), "uses an as-cast");
    SOFT_DENY.put(Pattern.compile("\\b(out|ref)\\s+\\w"), "passes an argument by reference");
    SOFT_DENY.put(Pattern.compile("\\bthrow\\s*;"), "rethrows with throw;");
    SOFT_DENY.put(Pattern.compile("\\bdelegate\\b"), "uses an anonymous delegate");
    SOFT_DENY.put(
        Pattern.compile("\\.(GroupBy|ThenBy|ThenByDescending|SelectMany|ToDictionary|Aggregate|Zip|ToArray|Last|LastOrDefault|Average|ToLookup|Join)\\s*\\("),
        "uses a LINQ operator with no single-step stream equivalent");
    SOFT_DENY.put(Pattern.compile("\\bstring\\.(Format|Concat|Compare)\\b"), "uses string.Format");
    SOFT_DENY.put(Pattern.compile("\\bEnum\\.\\w+"), "uses Enum helpers");
    SOFT_DENY.put(Pattern.compile("\\.Substring\\s*\\([^,()]*,"), "uses two-argument Substring, whose second argument is a length in C# and an end index in Java");
    SOFT_DENY.put(Pattern.compile("\\[\\s*\\^|\\w\\s*\\.\\.\\s*\\w"), "uses an index-from-end or range");
    SOFT_DENY.put(Pattern.compile("\\(\\s*var\\s+\\w+\\s*,|\\bvar\\s*\\("), "uses tuple deconstruction");
  }

  private Work softDeny(Work w) {
    for (Map.Entry<Pattern, String> rule : SOFT_DENY.entrySet()) {
      if (rule.getKey().matcher(w.text).find()) {
        throw new Bail(Tier.B, rule.getValue(), null);
      }
    }
    decimalArithmetic(w);
    return w;
  }

  /**
   * BigDecimal has no operators, so {@code book.Price * book.StockCount} cannot be rewritten token
   * by token. Any {@code decimal} local, {@code m} literal, or arithmetic next to a decimal property
   * sends the body to the model with a finding.
   */
  private void decimalArithmetic(Work w) {
    boolean hit =
        Pattern.compile("\\bdecimal\\b").matcher(w.text).find()
            || Pattern.compile("\\b\\d+(\\.\\d+)?[mM]\\b").matcher(w.text).find();
    for (String member : w.ctx.decimalMembers()) {
      String name = Pattern.quote(member);
      String operator = "(?:[-+*/%]|(?<![=-])[<>]=?)";
      hit |=
          Pattern.compile("\\." + name + "\\b\\s*" + operator + "(?![>=])")
              .matcher(w.text)
              .find();
      hit |=
          Pattern.compile("(?<![=>-])" + operator + "=?\\s*[\\w.()]*\\." + name + "\\b")
              .matcher(w.text)
              .find();
    }
    if (hit) {
      throw new Bail(
          Tier.B,
          "does arithmetic on decimal values, which become BigDecimal and have no operators in Java",
          FindingCode.DECIMAL_ARITHMETIC);
    }
  }

  private Work await(Work w) {
    Matcher m = Pattern.compile("\\bawait\\s+").matcher(w.text);
    if (m.find()) {
      w.note(FindingCode.ASYNC_DROPPED);
      w.text = m.replaceAll("");
    }
    // With the method synchronous, a completed task is just its value.
    w.text =
        CSharpText.replaceCalls(
            w.text,
            Pattern.compile("\\b(?:Value)?Task\\s*\\.\\s*FromResult\\s*(?:<[^>()]*>)?\\s*"),
            call -> call.args().size() == 1 ? call.args().get(0) : null);
    w.text = w.text.replaceAll("\\breturn\\s+(?:Value)?Task\\s*\\.\\s*CompletedTask\\s*;", "return;");
    if (Pattern.compile("\\b(?:Value)?Task\\s*\\.\\s*\\w+").matcher(w.text).find()) {
      throw new Bail(
          Tier.B,
          "uses the Task API directly (delays, WhenAll, Run), which has no synchronous equivalent"
              + " a rule can choose",
          FindingCode.ASYNC_DROPPED);
    }
    return w;
  }

  /**
   * EF's async query operators become their synchronous names, and loading hints that JPA
   * expresses through fetch strategy rather than per query are dropped.
   */
  private Work efQueryOperators(Work w) {
    w.text =
        w.text.replaceAll(
            "\\.(ToList|FirstOrDefault|First|SingleOrDefault|Single|Any|All|Count|Sum|Min|Max|Find|Add|AddRange|SaveChanges)Async\\s*\\(",
            ".$1(");
    for (String hint : List.of("Include", "ThenInclude", "AsNoTracking", "AsQueryable", "AsEnumerable")) {
      w.text = CSharpText.replaceCalls(w.text, Pattern.compile("\\s*\\." + hint + "\\s*"), c -> "");
    }
    return w;
  }

  /**
   * {@code _db.Books.Find(id)} becomes {@code bookRepository.findById(id).orElse(null)}, and so on
   * for the DbSet operations that have a direct Spring Data counterpart. A DbSet used as the head
   * of a LINQ chain becomes {@code findAll()} and the chain rule handles the rest. SaveChanges
   * disappears: the generated service is transactional, and JPA flushes on commit.
   */
  private Work dbSetToRepository(Work w) {
    for (Map.Entry<String, Map<String, String>> context : w.ctx.dbSets().entrySet()) {
      String field = Pattern.quote(context.getKey());
      w.text =
          w.text.replaceAll(
              "(?m)^[ \\t]*(?:this\\.)?" + field + "\\s*\\.\\s*SaveChanges\\s*\\(\\s*\\)\\s*;[ \\t]*\\r?\\n?",
              "");
      for (Map.Entry<String, String> dbSet : context.getValue().entrySet()) {
        String repository = RewriteContext.repositoryField(dbSet.getValue());
        String head = "(?:this\\.)?" + field + "\\s*\\.\\s*" + Pattern.quote(dbSet.getKey()) + "\\b";
        String before = w.text;
        String key = w.ctx.entityKeys().get(dbSet.getValue());
        if (key != null) {
          w.text =
              CSharpText.replaceCalls(
                  w.text,
                  Pattern.compile(head + "\\s*\\.\\s*(FirstOrDefault|SingleOrDefault|First|Single)\\s*"),
                  call -> keyLookup(repository, key, call.group(1), call.args()));
        }
        w.text =
            CSharpText.replaceCalls(
                w.text,
                Pattern.compile(head + "\\s*\\.\\s*(Find|Add|Update|Remove|AddRange|RemoveRange|ToList|Count|Any)\\s*"),
                call -> dbSetCall(repository, call.group(1), call.args()));
        w.text = w.text.replaceAll(head, Matcher.quoteReplacement(repository + ".findAll()"));
        if (!w.text.equals(before)) {
          w.repositories.add(dbSet.getValue());
        }
      }
    }
    return w;
  }

  /**
   * {@code _db.Books.FirstOrDefault(b => b.Id == id)} is a primary-key lookup. Left to the chain
   * rule it would become a stream over {@code findAll()}: correct, and a full table scan.
   */
  private static String keyLookup(
      String repository, String key, String operation, List<String> args) {
    if (args.size() != 1) {
      return null;
    }
    Matcher lambda =
        Pattern.compile("^(\\w+)\\s*=>\\s*(?:\\1\\s*\\.\\s*" + Pattern.quote(key)
                + "\\s*==\\s*(.+)|(.+?)\\s*==\\s*\\1\\s*\\.\\s*" + Pattern.quote(key) + ")$",
                Pattern.DOTALL)
            .matcher(args.get(0).strip());
    if (!lambda.matches()) {
      return null;
    }
    String value = (lambda.group(2) != null ? lambda.group(2) : lambda.group(3)).strip();
    // A value mentioning the lambda variable is a correlated condition, not a key lookup.
    if (Pattern.compile("\\b" + Pattern.quote(lambda.group(1)) + "\\b").matcher(value).find()) {
      return null;
    }
    boolean orNull = operation.endsWith("OrDefault");
    return repository + ".findById(" + value + ")" + (orNull ? ".orElse(null)" : ".orElseThrow()");
  }

  private static String dbSetCall(String repository, String operation, List<String> args) {
    String arg = args.isEmpty() ? "" : args.get(0);
    return switch (operation) {
      case "Find" -> args.size() == 1 ? repository + ".findById(" + arg + ").orElse(null)" : null;
      case "Add", "Update" -> repository + ".save(" + arg + ")";
      case "AddRange" -> repository + ".saveAll(" + arg + ")";
      case "Remove" -> repository + ".delete(" + arg + ")";
      case "RemoveRange" -> repository + ".deleteAll(" + arg + ")";
      case "ToList" -> args.isEmpty() ? repository + ".findAll()" : null;
      case "Count" -> args.isEmpty() ? repository + ".count()" : null;
      case "Any" -> args.isEmpty() ? "(" + repository + ".count() > 0)" : null;
      default -> null;
    };
  }

  private static final Pattern INITIALIZER_STATEMENT =
      Pattern.compile(
          "(?m)^([ \\t]*)(?:(return)\\s+|((?:var|[A-Z]\\w*(?:<[^;=]*>)?)\\s+)?(\\w+)\\s*=\\s*)new\\s+([A-Z]\\w*)\\s*(?:\\(\\s*\\))?\\s*\\{");

  /**
   * {@code var book = new Book { Title = t, Price = p };} becomes a constructor call followed by
   * one assignment per member, which the setter rule then turns into setter calls. {@code return new
   * X { ... };} gets a local first. Initializers in any other position, such as a lambda body, are
   * left for the residual check to catch.
   */
  private Work objectInitializer(Work w) {
    while (true) {
      Matcher m = INITIALIZER_STATEMENT.matcher(w.text);
      boolean found = false;
      while (m.find()) {
        String type = m.group(5);
        if (COLLECTION_TYPES.containsKey(type)) {
          continue;
        }
        int open = m.end() - 1;
        int close = CSharpText.matching(w.text, open);
        if (close < 0) {
          break;
        }
        int semicolon = close + 1;
        while (semicolon < w.text.length() && Character.isWhitespace(w.text.charAt(semicolon))) {
          semicolon++;
        }
        if (semicolon >= w.text.length() || w.text.charAt(semicolon) != ';') {
          continue;
        }
        String indent = m.group(1);
        boolean isReturn = m.group(2) != null;
        String variable = isReturn ? freshName(w.text, type) : m.group(4);
        String declaration = isReturn ? "var " : m.group(3) == null ? "" : m.group(3);

        StringBuilder out = new StringBuilder();
        out.append(indent).append(declaration).append(variable).append(" = new ").append(type).append("();");
        for (String entry : CSharpText.splitTopLevel(w.text.substring(open + 1, close), ',')) {
          if (entry.isBlank()) {
            continue;
          }
          int eq = entry.indexOf('=');
          if (eq < 0) {
            throw new Bail(Tier.B, "uses a collection initializer on " + type, null);
          }
          out.append('\n').append(indent).append(variable).append('.')
              .append(entry.substring(0, eq).strip()).append(" = ")
              .append(entry.substring(eq + 1).strip()).append(';');
        }
        if (isReturn) {
          out.append('\n').append(indent).append("return ").append(variable).append(';');
        }
        w.text = w.text.substring(0, m.start()) + out + w.text.substring(semicolon + 1);
        found = true;
        break;
      }
      if (!found) {
        return w;
      }
    }
  }

  /** A local variable name not already used in the body: {@code bookResponse}, then {@code result}. */
  private static String freshName(String text, String type) {
    for (String candidate :
        List.of(Names.fieldName(type), "result", Names.fieldName(type) + "Result")) {
      if (!Pattern.compile("\\b" + Pattern.quote(candidate) + "\\b").matcher(text).find()) {
        return candidate;
      }
    }
    return Names.fieldName(type) + "1";
  }

  private static final Pattern RESULT_HELPER =
      Pattern.compile(
          "(?<![\\w.])(Ok|NotFound|BadRequest|NoContent|Unauthorized|Forbid|Conflict|StatusCode|Created|CreatedAtAction|Accepted|UnprocessableEntity)\\b\\s*");

  /** {@code return Ok(x);} becomes {@code return ResponseEntity.ok(x);}, and the rest of the family. */
  private Work controllerResults(Work w) {
    w.text =
        CSharpText.replaceCalls(
            w.text,
            RESULT_HELPER,
            call -> {
              List<String> a = call.args();
              String x = a.isEmpty() ? null : a.get(0);
              String re = RESPONSE_ENTITY;
              return switch (call.group(1) + "/" + a.size()) {
                case "Ok/0" -> re + ".ok().build()";
                case "Ok/1" -> re + ".ok(" + x + ")";
                case "NotFound/0" -> re + ".notFound().build()";
                case "NotFound/1" -> re + ".status(" + HTTP_STATUS + ".NOT_FOUND).body(" + x + ")";
                case "BadRequest/0" -> re + ".badRequest().build()";
                case "BadRequest/1" -> re + ".badRequest().body(" + x + ")";
                case "NoContent/0" -> re + ".noContent().build()";
                case "Unauthorized/0" -> re + ".status(" + HTTP_STATUS + ".UNAUTHORIZED).build()";
                case "Forbid/0" -> re + ".status(" + HTTP_STATUS + ".FORBIDDEN).build()";
                case "Conflict/0" -> re + ".status(" + HTTP_STATUS + ".CONFLICT).build()";
                case "Conflict/1" -> re + ".status(" + HTTP_STATUS + ".CONFLICT).body(" + x + ")";
                case "StatusCode/1" -> re + ".status(" + x + ").build()";
                case "StatusCode/2" -> re + ".status(" + x + ").body(" + a.get(1) + ")";
                case "Created/2" -> re + ".created(" + ref("java.net.URI") + ".create(" + x + ")).body(" + a.get(1) + ")";
                case "Accepted/0" -> re + ".accepted().build()";
                case "UnprocessableEntity/1" -> re + ".unprocessableEntity().body(" + x + ")";
                case "CreatedAtAction/3" -> createdAtAction(w, a);
                default -> null;
              };
            });
    return w;
  }

  /**
   * {@code CreatedAtAction(nameof(GetById), new { id = created.Id }, created)} becomes a 201 whose
   * Location header is built from the target action's route. Possible only because the generator
   * knows every action's route template; without it the call is left for the model.
   */
  private String createdAtAction(Work w, List<String> args) {
    Matcher nameOf = Pattern.compile("nameof\\s*\\(\\s*(\\w+)\\s*\\)").matcher(args.get(0));
    Matcher values = Pattern.compile("(?s)new\\s*\\{(.*)\\}").matcher(args.get(1));
    if (!nameOf.matches() || !values.matches()) {
      return null;
    }
    String route = w.ctx.actionRoutes().get(nameOf.group(1));
    if (route == null) {
      return null;
    }
    Map<String, String> routeValues = new LinkedHashMap<>();
    for (String entry : CSharpText.splitTopLevel(values.group(1), ',')) {
      String e = entry.strip();
      int eq = e.indexOf('=');
      String key = eq < 0 ? e.substring(e.lastIndexOf('.') + 1) : e.substring(0, eq).strip();
      routeValues.put(key.toLowerCase(Locale.ROOT), eq < 0 ? e : e.substring(eq + 1).strip());
    }
    List<String> ordered = new ArrayList<>();
    Matcher placeholder = Pattern.compile("\\{(\\w+)[^}]*}").matcher(route);
    StringBuilder template = new StringBuilder();
    while (placeholder.find()) {
      String value = routeValues.get(placeholder.group(1).toLowerCase(Locale.ROOT));
      if (value == null) {
        return null;
      }
      ordered.add(value);
      placeholder.appendReplacement(template, Matcher.quoteReplacement("{" + placeholder.group(1) + "}"));
    }
    placeholder.appendTail(template);
    return RESPONSE_ENTITY
        + ".created("
        + ref("org.springframework.web.servlet.support.ServletUriComponentsBuilder")
        + ".fromCurrentContextPath().path("
        + w.literal(javaStringLiteral(template.toString()))
        + ").buildAndExpand("
        + String.join(", ", ordered)
        + ").toUri()).body("
        + args.get(2)
        + ")";
  }

  private static final Map<String, String> LOG_LEVELS =
      Map.of(
          "LogTrace", "trace",
          "LogDebug", "debug",
          "LogInformation", "info",
          "LogWarning", "warn",
          "LogError", "error",
          "LogCritical", "error");

  /**
   * {@code _logger.LogInformation("Book {Id} saved", id)} becomes {@code log.info("Book {} saved",
   * id)}. Message-template holes become SLF4J's positional {@code {}}, and an exception passed first,
   * as .NET does, moves last, where SLF4J expects it.
   */
  private Work logging(Work w) {
    for (String field : w.ctx.loggerFields()) {
      w.text =
          CSharpText.replaceCalls(
              w.text,
              Pattern.compile("(?:this\\.)?" + Pattern.quote(field) + "\\s*\\.\\s*(Log\\w+)\\s*"),
              call -> {
                String level = LOG_LEVELS.get(call.group(1));
                if (level == null || call.args().isEmpty()) {
                  return null;
                }
                List<String> args = new ArrayList<>(call.args());
                if (!isLiteral(args.get(0)) && args.size() > 1 && isLiteral(args.get(1))) {
                  args.add(args.remove(0));
                }
                if (isLiteral(args.get(0))) {
                  int index = literalIndex(args.get(0));
                  w.literals.set(index, w.literals.get(index).replaceAll("\\{[^{}]+}", "{}"));
                }
                return w.ctx.fieldRenames().getOrDefault(field, "log") + "." + level + "("
                    + String.join(", ", args) + ")";
              });
    }
    return w;
  }

  private static boolean isLiteral(String arg) {
    return arg.matches("\u0002\\d+\u0003");
  }

  private static int literalIndex(String placeholder) {
    return Integer.parseInt(placeholder.substring(1, placeholder.length() - 1));
  }

  /**
   * An action returning {@code ActionResult<T>} may {@code return value;} and C# wraps it in a 200.
   * Java needs the wrapping spelled out.
   */
  private Work implicitOk(Work w) {
    if (!w.ctx.wrapBareReturns()) {
      return w;
    }
    StringBuilder out = new StringBuilder();
    Matcher m = Pattern.compile("\\breturn\\s+").matcher(w.text);
    int from = 0;
    while (m.find(from)) {
      int end = CSharpText.endOfStatement(w.text, m.end());
      if (end < 0) {
        break;
      }
      String expression = w.text.substring(m.end(), end).strip();
      out.append(w.text, from, m.end());
      // Any ResponseEntity in the expression means C# built the result itself, as in
      // `x == null ? NotFound() : Ok(x)`. Only a plain value was implicitly wrapped.
      out.append(expression.contains(RESPONSE_ENTITY) ? expression : RESPONSE_ENTITY + ".ok(" + expression + ")");
      from = end;
    }
    out.append(w.text.substring(from));
    w.text = out.toString();
    return w;
  }

  private static final Pattern CONDITIONAL_COALESCE =
      Pattern.compile("([A-Za-z_][\\w.]*(?:\\(\\))?)\\?\\.([A-Za-z_]\\w*)\\s*\\?\\?\\s*");
  private static final Pattern CONDITIONAL_ACCESS =
      Pattern.compile("([A-Za-z_][\\w.]*)\\?\\.([A-Za-z_]\\w*)(?!\\s*\\()");
  private static final Pattern COALESCE = Pattern.compile("([\\w.]+(?:\\(\\))?)\\s*\\?\\?\\s*");

  /**
   * Null handling. {@code is null} becomes {@code == null}; {@code a?.B ?? c} becomes an Optional
   * chain; a bare {@code a?.B} becomes a conditional; {@code a ?? b} becomes {@code
   * Objects.requireNonNullElse}.
   */
  private Work nullChecks(Work w) {
    w.text = w.text.replaceAll("\\bis\\s+not\\s+null\\b", "!= null").replaceAll("\\bis\\s+null\\b", "== null");

    w.text =
        rewriteWithTail(
            w.text,
            CONDITIONAL_COALESCE,
            (m, fallback) -> {
              String v = lambdaName(w.text);
              return OPTIONAL + ".ofNullable(" + m.group(1) + ").map(" + v + " -> " + v + "."
                  + m.group(2) + ").orElse(" + fallback + ")";
            });
    Matcher access = CONDITIONAL_ACCESS.matcher(w.text);
    if (access.find()) {
      w.note(FindingCode.NULLABLE_REFERENCE);
      w.text =
          access.replaceAll(r -> Matcher.quoteReplacement(
              "(" + r.group(1) + " == null ? null : " + r.group(1) + "." + r.group(2) + ")"));
    }
    w.text =
        rewriteWithTail(
            w.text,
            COALESCE,
            (m, fallback) -> OBJECTS + ".requireNonNullElse(" + m.group(1) + ", " + fallback + ")");
    return w;
  }

  /**
   * Replaces each match of {@code head} together with the expression that follows it, up to the
   * next top-level {@code , ; ) ] }} boundary.
   */
  private static String rewriteWithTail(
      String text, Pattern head, java.util.function.BiFunction<Matcher, String, String> build) {
    Matcher m = head.matcher(text);
    StringBuilder out = new StringBuilder();
    int from = 0;
    while (m.find(from)) {
      int end = expressionEnd(text, m.end());
      String tail = text.substring(m.end(), end).strip();
      out.append(text, from, m.start()).append(build.apply(m, tail));
      from = end;
    }
    out.append(text.substring(from));
    return out.toString();
  }

  private static int expressionEnd(String text, int from) {
    int depth = 0;
    for (int i = from; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '(' || c == '[' || c == '{') {
        depth++;
      } else if (c == ')' || c == ']' || c == '}') {
        if (depth == 0) {
          return i;
        }
        depth--;
      } else if ((c == ',' || c == ';') && depth == 0) {
        return i;
      }
    }
    return text.length();
  }

  /** A lambda parameter name that cannot shadow a local, which Java forbids. */
  private static String lambdaName(String text) {
    for (String candidate : List.of("v", "value", "it", "item")) {
      if (!Pattern.compile("\\b" + candidate + "\\b").matcher(text).find()) {
        return candidate;
      }
    }
    return "v1";
  }

  private static final Pattern LINQ_OPERATOR =
      Pattern.compile(
          "\\.\\s*(Where|Select|OrderBy|OrderByDescending|Distinct|Skip|Take|ToList|Any|All|Count|First|FirstOrDefault|Single|SingleOrDefault|Sum|Min|Max)\\s*(?=\\()");

  private static final Set<String> TERMINALS =
      Set.of("ToList", "Any", "All", "Count", "First", "FirstOrDefault", "Single",
          "SingleOrDefault", "Sum", "Min", "Max");

  /**
   * A LINQ method chain becomes one stream pipeline: a single {@code .stream()} at the head, the
   * operators mapped in order, and exactly one terminal. Per-operator translation would put a
   * {@code .stream()} in front of every call, which is the classic mistake here.
   */
  private Work linqChains(Work w) {
    while (true) {
      Matcher m = LINQ_OPERATOR.matcher(w.text);
      if (!m.find()) {
        return w;
      }
      // Chains are usually split across lines, so the receiver ends before any
      // whitespace preceding the first operator's dot.
      int receiverEnd = m.start();
      while (receiverEnd > 0 && Character.isWhitespace(w.text.charAt(receiverEnd - 1))) {
        receiverEnd--;
      }
      int receiverStart = CSharpText.receiverStart(w.text, receiverEnd);
      String receiver = w.text.substring(receiverStart, receiverEnd).strip();
      if (receiver.isEmpty()) {
        throw new Bail(Tier.B, "uses a LINQ operator on an expression the rules cannot delimit", null);
      }

      List<String[]> operators = new ArrayList<>();
      int position = m.start();
      while (true) {
        Matcher next = LINQ_OPERATOR.matcher(w.text).region(position, w.text.length());
        if (!next.lookingAt()) {
          break;
        }
        int open = next.end();
        int close = CSharpText.matching(w.text, open);
        String args = w.text.substring(open + 1, close).strip();
        operators.add(new String[] {next.group(1), args});
        position = close + 1;
        if (TERMINALS.contains(next.group(1))) {
          break;
        }
        int ahead = position;
        while (ahead < w.text.length() && Character.isWhitespace(w.text.charAt(ahead))) {
          ahead++;
        }
        if (ahead < w.text.length() && w.text.charAt(ahead) == '.') {
          position = ahead;
        }
      }

      String java = streamPipeline(receiver, operators);
      w.text = w.text.substring(0, receiverStart) + java + w.text.substring(position);
    }
  }

  private String streamPipeline(String receiver, List<String[]> operators) {
    // A lone Count() or Any() on a collection is a size check, not a stream.
    if (operators.size() == 1 && operators.get(0)[1].isEmpty()) {
      switch (operators.get(0)[0]) {
        case "Count" -> {
          return receiver + ".size()";
        }
        case "Any" -> {
          return "!" + receiver + ".isEmpty()";
        }
        case "ToList" -> {
          return "new " + ref("java.util.ArrayList") + "<>(" + receiver + ")";
        }
        default -> {
          // fall through to the pipeline
        }
      }
    }
    StringBuilder java = new StringBuilder(receiver).append(".stream()");
    boolean terminated = false;
    for (String[] op : operators) {
      String name = op[0];
      String a = op[1];
      boolean hasArg = !a.isEmpty();
      java.append(
          switch (name) {
            case "Where" -> ".filter(" + a + ")";
            case "Select" -> ".map(" + a + ")";
            case "OrderBy" -> ".sorted(" + COMPARATOR + ".comparing(" + a + "))";
            case "OrderByDescending" ->
                ".sorted(" + COMPARATOR + ".comparing(" + a + ", " + COMPARATOR + ".reverseOrder()))";
            case "Distinct" -> ".distinct()";
            case "Skip" -> ".skip(" + a + ")";
            case "Take" -> ".limit(" + a + ")";
            case "ToList" -> ".toList()";
            case "Any" -> hasArg ? ".anyMatch(" + a + ")" : ".findAny().isPresent()";
            case "All" -> ".allMatch(" + a + ")";
            case "Count" -> (hasArg ? ".filter(" + a + ")" : "") + ".count()";
            case "First", "Single" -> (hasArg ? ".filter(" + a + ")" : "") + ".findFirst().orElseThrow()";
            case "FirstOrDefault", "SingleOrDefault" ->
                (hasArg ? ".filter(" + a + ")" : "") + ".findFirst().orElse(null)";
            case "Sum" -> ".mapToInt(" + a + ").sum()";
            case "Min" -> ".mapToInt(" + a + ").min().orElseThrow()";
            case "Max" -> ".mapToInt(" + a + ").max().orElseThrow()";
            default -> throw new Bail(Tier.B, "uses LINQ operator " + name, FindingCode.LINQ_COMPLEX);
          });
      terminated |= TERMINALS.contains(name);
    }
    if (!terminated) {
      // An unterminated chain was an IEnumerable in C#; the IR maps IEnumerable to List.
      java.append(".toList()");
    }
    return java.toString();
  }

  private Work foreach(Work w) {
    w.text =
        CSharpText.replaceCalls(
            w.text,
            Pattern.compile("\\bforeach\\s*"),
            call -> {
              String header = String.join(", ", call.args());
              Matcher in = Pattern.compile("^(.+?)\\s+in\\s+(.+)$", Pattern.DOTALL).matcher(header);
              return in.matches() ? "for (" + in.group(1) + " : " + in.group(2) + ")" : null;
            });
    return w;
  }

  /** The handful of framework calls with a direct JDK counterpart. */
  private Work frameworkStatics(Work w) {
    w.text =
        CSharpText.replaceCalls(
            w.text,
            Pattern.compile("\\bstring\\.(IsNullOrEmpty|IsNullOrWhiteSpace)\\s*"),
            call -> {
              if (call.args().size() != 1) {
                return null;
              }
              String a = call.args().get(0);
              String test = call.group(1).equals("IsNullOrEmpty") ? "isEmpty" : "isBlank";
              return "(" + a + " == null || " + a + "." + test + "())";
            });
    w.text = w.text.replaceAll("\\bstring\\.Empty\\b", Matcher.quoteReplacement(w.literal("\"\"")));
    w.text = w.text.replaceAll("\\bstring\\.Join\\s*\\(", "String.join(");
    w.text = w.text.replaceAll("\\bConsole\\.WriteLine\\s*\\(", "System.out.println(");
    w.text =
        w.text
            .replaceAll("\\bDateTime\\.UtcNow\\b",
                Matcher.quoteReplacement(ref("java.time.LocalDateTime") + ".now(" + ref("java.time.ZoneOffset") + ".UTC)"))
            .replaceAll("\\bDateTime\\.Now\\b", Matcher.quoteReplacement(ref("java.time.LocalDateTime") + ".now()"))
            .replaceAll("\\bDateTime\\.Today\\b", Matcher.quoteReplacement(ref("java.time.LocalDate") + ".now().atStartOfDay()"))
            .replaceAll("\\bGuid\\.NewGuid\\s*\\(\\s*\\)", Matcher.quoteReplacement(ref("java.util.UUID") + ".randomUUID()"))
            .replaceAll("\\.ToUpperInvariant\\s*\\(\\s*\\)", Matcher.quoteReplacement(".toUpperCase(" + ref("java.util.Locale") + ".ROOT)"))
            .replaceAll("\\.ToLowerInvariant\\s*\\(\\s*\\)", Matcher.quoteReplacement(".toLowerCase(" + ref("java.util.Locale") + ".ROOT)"))
            .replaceAll("\\.ToUpper\\s*\\(\\s*\\)", ".toUpperCase()")
            .replaceAll("\\.ToLower\\s*\\(\\s*\\)", ".toLowerCase()")
            .replaceAll("\\.TrimStart\\s*\\(\\s*\\)", ".stripLeading()")
            .replaceAll("\\.TrimEnd\\s*\\(\\s*\\)", ".stripTrailing()")
            .replaceAll("\\.Trim\\s*\\(\\s*\\)", ".strip()")
            .replaceAll("\\b(\\w+)\\.HasValue\\b", "($1 != null)");
    return w;
  }

  private static final Map<String, String> EXCEPTIONS = new LinkedHashMap<>();

  static {
    EXCEPTIONS.put("ArgumentNullException", "IllegalArgumentException");
    EXCEPTIONS.put("ArgumentOutOfRangeException", "IllegalArgumentException");
    EXCEPTIONS.put("ArgumentException", "IllegalArgumentException");
    EXCEPTIONS.put("InvalidOperationException", "IllegalStateException");
    EXCEPTIONS.put("KeyNotFoundException", ref("java.util.NoSuchElementException"));
    EXCEPTIONS.put("NotImplementedException", "UnsupportedOperationException");
    EXCEPTIONS.put("NotSupportedException", "UnsupportedOperationException");
    EXCEPTIONS.put("FormatException", "IllegalArgumentException");
    EXCEPTIONS.put("UnauthorizedAccessException", "SecurityException");
  }

  private Work exceptions(Work w) {
    for (Map.Entry<String, String> e : EXCEPTIONS.entrySet()) {
      w.text = w.text.replaceAll("\\b" + e.getKey() + "\\b", Matcher.quoteReplacement(e.getValue()));
    }
    // A plain Exception is checked in Java and would need a throws clause.
    w.text = w.text.replaceAll("\\bnew\\s+Exception\\s*\\(", "new RuntimeException(");
    return w;
  }

  private static final Map<String, String[]> COLLECTION_TYPES = new LinkedHashMap<>();

  static {
    // name -> {declared interface, concrete type for `new`}
    for (String n : List.of("List", "IList", "IEnumerable", "IReadOnlyList")) {
      COLLECTION_TYPES.put(n, new String[] {"java.util.List", "java.util.ArrayList"});
    }
    for (String n : List.of("ICollection", "IReadOnlyCollection")) {
      COLLECTION_TYPES.put(n, new String[] {"java.util.Collection", "java.util.ArrayList"});
    }
    for (String n : List.of("Dictionary", "IDictionary", "IReadOnlyDictionary")) {
      COLLECTION_TYPES.put(n, new String[] {"java.util.Map", "java.util.HashMap"});
    }
    for (String n : List.of("HashSet", "ISet")) {
      COLLECTION_TYPES.put(n, new String[] {"java.util.Set", "java.util.HashSet"});
    }
  }

  private static final Map<String, String> BOXED = new LinkedHashMap<>();
  private static final Map<String, String> UNBOXED = new LinkedHashMap<>();

  static {
    BOXED.put("string", "String");
    BOXED.put("int", "Integer");
    BOXED.put("long", "Long");
    BOXED.put("bool", "Boolean");
    BOXED.put("double", "Double");
    BOXED.put("float", "Float");
    BOXED.put("char", "Character");
    BOXED.put("short", "Short");
    BOXED.put("byte", "Byte");
    BOXED.put("object", "Object");
    UNBOXED.put("string", "String");
    UNBOXED.put("bool", "boolean");
    UNBOXED.put("object", "Object");
    for (Map<String, String> map : List.of(BOXED, UNBOXED)) {
      map.put("Guid", ref("java.util.UUID"));
      map.put("DateTime", ref("java.time.LocalDateTime"));
      map.put("DateOnly", ref("java.time.LocalDate"));
      map.put("TimeSpan", ref("java.time.Duration"));
    }
  }

  /** Type names written in the body: locals, generic arguments, {@code new List<T>()}. */
  private Work localTypes(Work w) {
    // new List<T> { a, b } -> new ArrayList<T>(List.of(a, b))
    Matcher init =
        Pattern.compile("\\bnew\\s+(" + String.join("|", COLLECTION_TYPES.keySet()) + ")\\s*(<)").matcher(w.text);
    while (init.find()) {
      int close = CSharpText.matching(w.text, init.start(2));
      int brace = close + 1;
      while (brace < w.text.length() && Character.isWhitespace(w.text.charAt(brace))) {
        brace++;
      }
      if (brace < w.text.length() && w.text.charAt(brace) == '{') {
        int end = CSharpText.matching(w.text, brace);
        String items = w.text.substring(brace + 1, end).strip();
        String[] types = COLLECTION_TYPES.get(init.group(1));
        if (types[0].equals("java.util.Map")) {
          throw new Bail(Tier.B, "uses a dictionary initializer", null);
        }
        w.text = w.text.substring(0, close + 1) + "(" + ref(types[0]) + ".of(" + items + "))" + w.text.substring(end + 1);
        init = Pattern.compile("\\bnew\\s+(" + String.join("|", COLLECTION_TYPES.keySet()) + ")\\s*(<)").matcher(w.text);
      }
    }
    w.text =
        Pattern.compile("(\\bnew\\s+)?\\b(" + String.join("|", COLLECTION_TYPES.keySet()) + ")\\s*(?=<)")
            .matcher(w.text)
            .replaceAll(
                r -> {
                  String[] types = COLLECTION_TYPES.get(r.group(2));
                  return Matcher.quoteReplacement(
                      r.group(1) == null ? ref(types[0]) : r.group(1) + ref(types[1]));
                });
    // Generic arguments must be boxed: List<int> is not Java.
    StringBuilder out = new StringBuilder();
    int from = 0;
    Matcher generic = Pattern.compile(JavaImports.CLOSE + "<").matcher(w.text);
    while (generic.find(from)) {
      int open = generic.end() - 1;
      int close = CSharpText.matching(w.text, open);
      if (close < 0) {
        break;
      }
      out.append(w.text, from, open);
      out.append(mapWords(w.text.substring(open, close + 1), BOXED));
      from = close + 1;
    }
    out.append(w.text.substring(from));
    w.text = out.toString();
    // Nullable value-type locals: int? x -> Integer x
    w.text =
        Pattern.compile("\\b(int|long|bool|double|float|char|short|byte|Guid|DateTime)\\?(?=\\s+\\w|\\s*\\))")
            .matcher(w.text)
            .replaceAll(r -> Matcher.quoteReplacement(BOXED.get(r.group(1))));
    w.text = mapWords(w.text, UNBOXED);
    return w;
  }

  private static String mapWords(String text, Map<String, String> words) {
    Matcher m =
        Pattern.compile("(?<![\\w." + JavaImports.OPEN + "])(" + String.join("|", words.keySet()) + ")\\b(?!\\s*\\.)")
            .matcher(text);
    return m.replaceAll(r -> Matcher.quoteReplacement(words.get(r.group(1))));
  }

  private Work collectionSize(Work w) {
    w.text =
        w.text
            .replaceAll("(?<=[\\w)\\]])\\.Count\\b(?!\\s*\\()", ".size()")
            .replaceAll("(?<=[\\w)\\]])\\.Length\\b(?!\\s*\\()", ".length()");
    return w;
  }

  /** {@code book.Title = x;} becomes {@code book.setTitle(x);} for known properties. */
  private Work propertySetters(Work w) {
    if (w.ctx.properties().isEmpty()) {
      return w;
    }
    Pattern assignment =
        Pattern.compile(
            "(?<=[\\w)\\]])\\.(" + String.join("|", w.ctx.properties().keySet()) + ")\\s*([-+*/]?)=(?![=>])");
    while (true) {
      Matcher m = assignment.matcher(w.text);
      if (!m.find()) {
        return w;
      }
      if (!m.group(2).isEmpty()) {
        throw new Bail(Tier.B, "uses compound assignment on property " + m.group(1), null);
      }
      int end = CSharpText.endOfStatement(w.text, m.end());
      if (end < 0) {
        throw new Bail(Tier.B, "assigns property " + m.group(1) + " inside an expression", null);
      }
      String value = w.text.substring(m.end(), end).strip();
      String setter = w.ctx.properties().get(m.group(1)).setter();
      w.text = w.text.substring(0, m.start()) + "." + setter + "(" + value + ")" + w.text.substring(end);
    }
  }

  /**
   * {@code book.Title} becomes {@code book.getTitle()} for known properties, except where the
   * receiver is a type name: {@code Genre.Fiction} is an enum constant, not a property read.
   */
  private Work propertyGetters(Work w) {
    if (w.ctx.properties().isEmpty()) {
      return w;
    }
    Pattern read =
        Pattern.compile(
            "(?<=[\\w)\\]])\\.(" + String.join("|", w.ctx.properties().keySet()) + ")\\b(?!\\s*\\()");
    Matcher m = read.matcher(w.text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      int receiverStart = CSharpText.receiverStart(w.text, m.start());
      String receiver = w.text.substring(receiverStart, m.start());
      String head = receiver.split("\\.", 2)[0];
      boolean isTypeReceiver =
          w.ctx.typeNames().contains(head) || receiver.indexOf(JavaImports.CLOSE) >= 0;
      String replacement =
          isTypeReceiver ? m.group() : "." + w.ctx.properties().get(m.group(1)).getter() + "()";
      m.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(out);
    w.text = out.toString();
    return w;
  }

  private Work fieldRenames(Work w) {
    // Longest first, so `_options.Value -> options` wins over `_options -> options`.
    List<Map.Entry<String, String>> renames = new ArrayList<>(w.ctx.fieldRenames().entrySet());
    renames.sort((a, b) -> b.getKey().length() - a.getKey().length());
    for (Map.Entry<String, String> rename : renames) {
      w.text =
          w.text.replaceAll(
              "(?<![\\w.])" + Pattern.quote(rename.getKey()) + "\\b",
              Matcher.quoteReplacement(rename.getValue()));
    }
    return w;
  }

  /** PascalCase method calls become camelCase, and the C# Async suffix goes with the asynchrony. */
  private Work methodNames(Work w) {
    for (Map.Entry<String, String> own : w.ctx.ownMethods().entrySet()) {
      w.text =
          w.text.replaceAll(
              "(?<![\\w." + JavaImports.CLOSE + "])(?<!new\\s)" + Pattern.quote(own.getKey()) + "(?=\\s*\\()",
              Matcher.quoteReplacement(own.getValue()));
    }
    w.text =
        Pattern.compile("\\.([A-Z]\\w*)(?=\\s*\\()")
            .matcher(w.text)
            .replaceAll(r -> Matcher.quoteReplacement("." + Names.methodName(r.group(1))));
    return w;
  }

  /** {@code x == "a"} compares references in Java; the literal-first equals is null-safe too. */
  private Work stringEquality(Work w) {
    String literal = "(\\u0002\\d+\\u0003)";
    String operand = "([A-Za-z_][\\w.]*(?:\\(\\))?)";
    w.text =
        w.text
            .replaceAll(operand + "\\s*==\\s*" + literal, "$2.equals($1)")
            .replaceAll(operand + "\\s*!=\\s*" + literal, "!$2.equals($1)")
            .replaceAll(literal + "\\s*==\\s*" + operand, "$1.equals($2)");
    return w;
  }

  private static Work replace(Work w, String from, String to) {
    w.text = w.text.replace(from, to);
    return w;
  }

  // ----------------------------------------------------------- checks

  private static final Map<Pattern, String> RESIDUAL = new LinkedHashMap<>();

  static {
    RESIDUAL.put(Pattern.compile("=>"), "a lambda the rules could not convert");
    RESIDUAL.put(Pattern.compile("\\?\\.|\\?\\?"), "null-conditional access the rules could not convert");
    RESIDUAL.put(Pattern.compile("\\bawait\\b"), "await");
    RESIDUAL.put(Pattern.compile("\\bforeach\\b"), "a foreach the rules could not convert");
    RESIDUAL.put(Pattern.compile("\\bnameof\\b"), "nameof");
    RESIDUAL.put(Pattern.compile("\\bnew\\s*\\{"), "an anonymous object");
    RESIDUAL.put(Pattern.compile("\\bnew\\s+[\\w<>, ]+\\s*\\{"), "an object initializer inside an expression");
    RESIDUAL.put(Pattern.compile("\\bnew\\s*\\(\\s*\\)"), "a target-typed new()");
    RESIDUAL.put(Pattern.compile("\\.[A-Z]\\w*\\s*\\("), "a call the rules did not recognise");
    RESIDUAL.put(Pattern.compile("(?:\\b[a-z_]\\w*|\\))\\.[A-Z]\\w*\\b"), "a member the rules did not recognise");
    RESIDUAL.put(Pattern.compile("(?<!\\.)\\b(string|bool|decimal|object|dynamic|const|readonly|is|as|out|ref|using|lock)\\b"),
        "a C# keyword with no rule");
    RESIDUAL.put(Pattern.compile("\\b[a-z]\\w*\\?\\s+\\w+\\s*[=;]"), "a nullable local");
    RESIDUAL.put(Pattern.compile("@|\\$"), "a C#-only token");
  }

  private static void residual(Work w) {
    String text = w.text.replaceAll(JavaImports.OPEN + "[\\w.]+" + JavaImports.CLOSE, "T");
    // Checked first: a leftover DbContext reference is the most useful thing to
    // tell the reviewer, and it would otherwise surface as a vaguer residual.
    for (String field : w.ctx.dbSets().keySet()) {
      String javaName = w.ctx.fieldRenames().getOrDefault(field, field);
      if (Pattern.compile("(?<![\\w.])(" + Pattern.quote(field) + "|" + Pattern.quote(javaName) + ")\\b")
          .matcher(text)
          .find()) {
        throw new Bail(
            Tier.B,
            "uses the EF DbContext in a way that has no repository equivalent",
            FindingCode.EF_FLUENT_CONFIG);
      }
    }
    for (Map.Entry<Pattern, String> rule : RESIDUAL.entrySet()) {
      Matcher m = rule.getKey().matcher(text);
      if (m.find()) {
        String snippet = new CSharpText.Masked(m.group(), w.literals).restore().strip();
        throw new Bail(Tier.B, "contains " + rule.getValue() + " (" + snippet + ")", null);
      }
    }
  }

  /** The rewritten body must at least parse as Java before it can be called a rule translation. */
  private void checkSyntax(String statements) {
    String probe =
        "class Probe { Object probe() {\n" + JavaImports.toSimpleNames(statements) + "\n} }";
    try {
      formatter.formatSource(probe);
    } catch (FormatterException | RuntimeException e) {
      throw new Bail(Tier.B, "rewrote to text that does not parse as Java", null);
    }
  }
}
