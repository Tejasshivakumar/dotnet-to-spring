# Parser notes

## Where the grammar came from

`CSharpLexer.g4` and `CSharpParser.g4` are vendored from
[antlr/grammars-v4](https://github.com/antlr/grammars-v4), directory `csharp/v7`,
along with the Java base classes `CSharpLexerBase` and `CSharpParserBase`. MIT licensed.

They are vendored rather than pulled as a dependency because they need patching
(below) and because a grammar that changes underneath you changes your output.

The upstream repo has three C# grammars: `v6`, `v7` and `v8-spec`. `v7` was chosen
over `v8-spec` because `v8-spec` carries embedded target-specific semantic actions
(`this.ExitCurrentScope()`), which tie the grammar to a scope-tracking base class we
do not need. `v7` needed exactly one patch to accept the sample project.

## Patches applied

### File-scoped namespaces

The v7 grammar predates C# 10, so it accepts only

```csharp
namespace Bookstore.Models { ... }
```

Every .NET 6+ project template instead emits

```csharp
namespace Bookstore.Models;
```

`namespace_declaration` gained a second, labelled alternative for the file-scoped
form. Both are supported; the visitor treats them identically. Without this patch
essentially no modern C# parses at all.

## The supported subset

Measured, not asserted. `CSharpSourceParserTest` parses every `.cs` file in
`sample-dotnet/` and fails the build on any syntax error, and `SupportedSubsetTest`
pins down each rejection and each construct that must survive into the IR intact.

A file is accepted only if it both parses and passes `SubsetValidator`. The grammar
accepts much more than the IR can represent; without the validator a pointer field
parsed cleanly and quietly became an `int`.

### Accepted and preserved in the IR

- Namespaces, file-scoped or block-scoped, including nested ones, as long as every
  type in the file sits in one namespace
- Using directives at file level and inside a block namespace
- Classes, interfaces, enums, structs
- Fields, auto-properties, properties with accessor logic (getter and setter bodies
  are kept as source text), methods, constructors
- Expression-bodied methods and properties, kept exactly as written (`=> expr`); the
  body rewriter decides whether that becomes `return expr;`, `expr;` or `throw ...;`
- `params` arrays, kept as varargs parameters; `ref`, `out` and `in` parameters, kept
  with their modifier so the method can be routed to manual review
- Attributes with positional and named arguments
- Generics, arrays, nullable value types (`int?`) and nullable reference types (`string?`)
- `async` / `await`, LINQ method syntax, string interpolation, lambdas
- XML doc comments (on a separate token channel)

### Rejected, with the construct named and its line

| Construct | Why |
|---|---|
| `record` types | v7 predates C# 9: reported as a syntax error |
| `unsafe`, pointer types, `fixed`, `stackalloc` | No Java equivalent |
| LINQ query syntax (`from x in y select`) | Method syntax only, by choice |
| Nested type declarations | Hoisting one changes its name and its access to the outer class's private members |
| Types in more than one namespace in one file | The IR records one namespace per file |
| Indexers, operator overloads, conversion operators | No Spring-shaped equivalent worth guessing at |
| Events, finalizers | Same |

The rejection becomes an `UNSUPPORTED_CONSTRUCT` finding and the rest of the project
still migrates.

Rejection is deliberate and loud. A parser that silently produces a half-built IR
for a file it did not understand produces plausible-looking Java that is wrong,
which is worse than a clear error naming the construct.

### Handled outside the grammar

| Input | What happens |
|---|---|
| `Program.cs` / `Startup.cs` (top-level statements, C# 9) | Not parsed. `ProgramScanner` reads DI registrations (`AddScoped<IFoo, Foo>`) and `AddDbContext<T>` from the raw text with targeted regexes. Registrations built at runtime are invisible to it, and classification falls back to naming conventions. |
| `#if` and other preprocessor directives | Evaluated by the vendored lexer with **no symbols defined**: `#if DEBUG` takes its `#else` branch, `#if false` code disappears. The IR reflects a Release build with no custom symbols. |
| `.csproj` | Read as XML for `TargetFramework` and `PackageReference`s. External entities are disabled. |
| `appsettings.json` | Read with Jackson into an ordered map. |

## Why the parse tree is not the IR

ANTLR contexts are tied to the grammar: every grammar patch would ripple through
every consumer. The visitor converts the tree into the IR in `com.portway.core.ir`
once, and everything downstream — classification, rules, generation — reads only
the IR. The grammar is an implementation detail of one package.
