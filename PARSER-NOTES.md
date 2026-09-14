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

Measured, not asserted: `CSharpSourceParserTest` parses every `.cs` file in
`sample-dotnet/` and fails the build on any syntax error.

### Parsed by the grammar

- Namespaces, both file-scoped and block-scoped
- Classes, interfaces, enums, structs
- Fields, auto-properties and properties with bodies, methods, constructors
- Attributes with positional and named arguments
- Generics, arrays, nullable value types (`int?`) and nullable reference types (`string?`)
- `async` / `await`, LINQ method syntax, string interpolation, lambdas
- XML doc comments (on a separate token channel)

### Not parsed, and what happens instead

| Construct | Why | What happens |
|---|---|---|
| Top-level statements (`Program.cs`) | v7 predates C# 9 | Handled by a dedicated scanner that looks only for DI registrations and `AddDbContext`, not by the full parser |
| `record` types | v7 predates C# 9 | Syntax error, file rejected with the construct named |
| `#if` and other preprocessor directives | Directives go to a separate channel and are not evaluated | Code inside them is parsed as if the directive were absent |
| `unsafe`, `stackalloc`, pointer types | Out of scope | Syntax error, file rejected |
| LINQ query syntax (`from x in y select`) | Method syntax only, by choice | Syntax error, file rejected |

Rejection is deliberate and loud. A parser that silently produces a half-built IR
for a file it did not understand produces plausible-looking Java that is wrong,
which is worse than a clear error naming the construct.

## Why the parse tree is not the IR

ANTLR contexts are tied to the grammar: every grammar patch would ripple through
every consumer. The visitor converts the tree into the IR in `com.portway.core.ir`
once, and everything downstream — classification, rules, generation — reads only
the IR. The grammar is an implementation detail of one package.
