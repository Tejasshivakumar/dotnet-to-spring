package com.portway.core.classify;

import com.portway.core.ir.SourceProject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recovers dependency-injection registrations from Program.cs without parsing it.
 *
 * <p>Program.cs uses top-level statements, which the vendored C# 7 grammar predates. Rather than
 * patch the grammar for a file we need exactly two facts from, those facts are read with targeted
 * regexes: which interface maps to which implementation, and which type is the DbContext.
 *
 * <p>This is a deliberate limit, not an oversight. Registrations built at runtime — a loop, a
 * conditional, a scanning extension method — are invisible here, and the classifier falls back to
 * naming conventions when they are.
 */
public final class ProgramScanner {

  /** {@code AddScoped<IBookService, BookService>()} and the Transient and Singleton variants. */
  private static final Pattern REGISTRATION =
      Pattern.compile(
          "Add(?:Scoped|Transient|Singleton)\\s*<\\s*([\\w.]+)\\s*,\\s*([\\w.]+)\\s*>");

  /** {@code AddScoped<BookService>()} with no separate interface. */
  private static final Pattern SELF_REGISTRATION =
      Pattern.compile("Add(?:Scoped|Transient|Singleton)\\s*<\\s*([\\w.]+)\\s*>\\s*\\(");

  /** {@code AddDbContext<BookstoreDbContext>(...)}. */
  private static final Pattern DB_CONTEXT =
      Pattern.compile("AddDbContext\\s*<\\s*([\\w.]+)\\s*>");

  private ProgramScanner() {}

  /**
   * What Program.cs registered.
   *
   * @param serviceInterfaces interface names used as the service type of a registration
   * @param implementations concrete types used as the implementation type
   * @param interfaceToImplementation the pairing, so the generator knows which impl serves which
   * @param dbContexts types registered with AddDbContext
   */
  public record Registrations(
      Set<String> serviceInterfaces,
      Set<String> implementations,
      Map<String, String> interfaceToImplementation,
      Set<String> dbContexts) {

    static final Registrations EMPTY =
        new Registrations(Set.of(), Set.of(), Map.of(), Set.of());
  }

  /**
   * Scans the project's Program.cs or Startup.cs.
   *
   * <p>Reads from the raw source text carried on the IR's entry-point file. When the project has no
   * such file, every set comes back empty and classification falls through to naming conventions.
   */
  public static Registrations registrations(SourceProject project) {
    String source = project.entryPointSource();
    if (source == null || source.isBlank()) {
      return Registrations.EMPTY;
    }
    return scan(source);
  }

  /** Scans raw C# source. Exposed for tests and for callers holding the text directly. */
  public static Registrations scan(String source) {
    Set<String> interfaces = new LinkedHashSet<>();
    Set<String> implementations = new LinkedHashSet<>();
    Map<String, String> pairs = new LinkedHashMap<>();
    Set<String> contexts = new LinkedHashSet<>();

    Matcher registration = REGISTRATION.matcher(source);
    while (registration.find()) {
      String serviceType = simpleName(registration.group(1));
      String implementationType = simpleName(registration.group(2));
      interfaces.add(serviceType);
      implementations.add(implementationType);
      pairs.put(serviceType, implementationType);
    }

    Matcher self = SELF_REGISTRATION.matcher(source);
    while (self.find()) {
      implementations.add(simpleName(self.group(1)));
    }

    Matcher context = DB_CONTEXT.matcher(source);
    while (context.find()) {
      contexts.add(simpleName(context.group(1)));
    }

    return new Registrations(interfaces, implementations, pairs, contexts);
  }

  private static String simpleName(String name) {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? name : name.substring(dot + 1);
  }
}
