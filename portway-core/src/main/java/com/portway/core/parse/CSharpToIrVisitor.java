package com.portway.core.parse;

import com.portway.core.ir.AttributeUse;
import com.portway.core.ir.ClassRole;
import com.portway.core.ir.ConstructorDecl;
import com.portway.core.ir.FieldDecl;
import com.portway.core.ir.MethodDecl;
import com.portway.core.ir.ParamDecl;
import com.portway.core.ir.PropertyDecl;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.TypeDecl;
import com.portway.core.ir.TypeKind;
import com.portway.core.ir.TypeRef;
import com.portway.core.parse.grammar.CSharpParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * Converts an ANTLR parse tree into the IR.
 *
 * <p>The conversion is structural only: types, members, signatures and attributes. Method bodies
 * are carried across as raw source text and never interpreted here. Modelling C# statements and
 * expressions would be writing a compiler, and the whole design rests on not doing that.
 *
 * <p>This is the one class allowed to know both the grammar and the IR. Everything downstream reads
 * the IR alone, so a grammar patch cannot ripple past this file.
 */
public class CSharpToIrVisitor {

  private final CommonTokenStream tokens;

  public CSharpToIrVisitor(CommonTokenStream tokens) {
    this.tokens = tokens;
  }

  /** Converts a parsed file. Types nested inside namespaces are flattened into one list. */
  public SourceFile toSourceFile(ParseResult parsed) {
    CSharpParser.Compilation_unitContext unit = parsed.tree();

    List<String> usings = new ArrayList<>();
    if (unit.using_directives() != null) {
      for (CSharpParser.Using_directiveContext u : unit.using_directives().using_directive()) {
        String name = usingName(u);
        if (name != null) {
          usings.add(name);
        }
      }
    }

    Namespace collected = new Namespace();
    if (unit.namespace_member_declarations() != null) {
      collectMembers(unit.namespace_member_declarations(), "", collected);
    }

    return new SourceFile(parsed.path(), collected.name, usings, collected.types);
  }

  /**
   * The namespace a using directive brings in. Alias and static forms are recorded by their target
   * namespace too: the generator only ever asks "was this namespace imported".
   */
  private String usingName(CSharpParser.Using_directiveContext ctx) {
    if (ctx instanceof CSharpParser.UsingNamespaceDirectiveContext ns) {
      return textOf(ns.namespace_or_type_name());
    }
    if (ctx instanceof CSharpParser.UsingAliasDirectiveContext alias) {
      return textOf(alias.namespace_or_type_name());
    }
    if (ctx instanceof CSharpParser.UsingStaticDirectiveContext stat) {
      return textOf(stat.namespace_or_type_name());
    }
    return null;
  }

  /** Accumulator for the recursive namespace walk. */
  private static final class Namespace {
    private String name = "";
    private final List<TypeDecl> types = new ArrayList<>();
  }

  private void collectMembers(
      CSharpParser.Namespace_member_declarationsContext ctx, String prefix, Namespace out) {
    for (CSharpParser.Namespace_member_declarationContext member : ctx.namespace_member_declaration()) {
      if (member.namespace_declaration() != null) {
        collectNamespace(member.namespace_declaration(), prefix, out);
      } else if (member.type_declaration() != null) {
        TypeDecl type = toTypeDecl(member.type_declaration());
        if (type != null) {
          out.types.add(type);
        }
      }
    }
  }

  private void collectNamespace(
      CSharpParser.Namespace_declarationContext ctx, String prefix, Namespace out) {
    if (ctx instanceof CSharpParser.Block_scoped_namespaceContext block) {
      String qualified = qualify(prefix, textOf(block.qualified_identifier()), out);
      CSharpParser.Namespace_bodyContext body = block.namespace_body();
      if (body != null && body.namespace_member_declarations() != null) {
        collectMembers(body.namespace_member_declarations(), qualified, out);
      }
    } else if (ctx instanceof CSharpParser.File_scoped_namespaceContext fileScoped) {
      String qualified = qualify(prefix, textOf(fileScoped.qualified_identifier()), out);
      if (fileScoped.namespace_member_declarations() != null) {
        collectMembers(fileScoped.namespace_member_declarations(), qualified, out);
      }
    }
  }

  /**
   * Joins a nested namespace onto its parent, recording the first one seen as the file's namespace.
   * Nesting is rare enough that flattening it beats modelling scope.
   */
  private String qualify(String prefix, String name, Namespace out) {
    String qualified = prefix.isEmpty() ? name : prefix + "." + name;
    if (out.name.isEmpty()) {
      out.name = qualified;
    }
    return qualified;
  }

  // ---------------------------------------------------------------- types

  private TypeDecl toTypeDecl(CSharpParser.Type_declarationContext ctx) {
    List<AttributeUse> attributes = toAttributes(ctx.attributes());
    List<String> modifiers = toModifiers(ctx.all_member_modifiers());
    String doc = SourceText.docCommentAbove(tokens, ctx);

    if (ctx.class_definition() != null) {
      return classDecl(ctx.class_definition(), TypeKind.CLASS, attributes, modifiers, doc);
    }
    if (ctx.interface_definition() != null) {
      return interfaceDecl(ctx.interface_definition(), attributes, modifiers, doc);
    }
    if (ctx.enum_definition() != null) {
      return enumDecl(ctx.enum_definition(), attributes, modifiers, doc);
    }
    if (ctx.struct_definition() != null) {
      return structDecl(ctx.struct_definition(), attributes, modifiers, doc);
    }
    // Delegates carry no members worth migrating and become functional interfaces
    // by hand if at all. Skipped rather than half-modelled.
    return null;
  }

  private TypeDecl classDecl(
      CSharpParser.Class_definitionContext ctx,
      TypeKind kind,
      List<AttributeUse> attributes,
      List<String> modifiers,
      String doc) {
    Members members = new Members();
    if (ctx.class_body() != null && ctx.class_body().class_member_declarations() != null) {
      collectClassMembers(ctx.class_body().class_member_declarations(), members);
    }
    return new TypeDecl(
        kind,
        ctx.identifier().getText(),
        modifiers,
        baseTypes(ctx.class_base()),
        attributes,
        members.fields,
        members.properties,
        members.methods,
        members.constructors,
        doc,
        ClassRole.UNKNOWN);
  }

  private TypeDecl interfaceDecl(
      CSharpParser.Interface_definitionContext ctx,
      List<AttributeUse> attributes,
      List<String> modifiers,
      String doc) {
    Members members = new Members();
    if (ctx.class_body() != null && ctx.class_body().class_member_declarations() != null) {
      collectClassMembers(ctx.class_body().class_member_declarations(), members);
    }
    List<TypeRef> bases = new ArrayList<>();
    if (ctx.interface_base() != null && ctx.interface_base().interface_type_list() != null) {
      for (CSharpParser.Namespace_or_type_nameContext n :
          ctx.interface_base().interface_type_list().namespace_or_type_name()) {
        bases.add(toTypeRef(n));
      }
    }
    return new TypeDecl(
        TypeKind.INTERFACE,
        ctx.identifier().getText(),
        modifiers,
        bases,
        attributes,
        members.fields,
        members.properties,
        members.methods,
        members.constructors,
        doc,
        ClassRole.UNKNOWN);
  }

  private TypeDecl structDecl(
      CSharpParser.Struct_definitionContext ctx,
      List<AttributeUse> attributes,
      List<String> modifiers,
      String doc) {
    Members members = new Members();
    if (ctx.struct_body() != null && ctx.struct_body().struct_member_declaration() != null) {
      for (CSharpParser.Struct_member_declarationContext m : ctx.struct_body().struct_member_declaration()) {
        if (m.common_member_declaration() != null) {
          collectCommonMember(
              m.common_member_declaration(),
              toAttributes(m.attributes()),
              toModifiers(m.all_member_modifiers()),
              m,
              members);
        }
      }
    }
    return new TypeDecl(
        TypeKind.STRUCT,
        ctx.identifier().getText(),
        modifiers,
        List.of(),
        attributes,
        members.fields,
        members.properties,
        members.methods,
        members.constructors,
        doc,
        ClassRole.UNKNOWN);
  }

  /**
   * Enum members become fields carrying their explicit value, which is all the generator needs to
   * emit a Java enum.
   */
  private TypeDecl enumDecl(
      CSharpParser.Enum_definitionContext ctx,
      List<AttributeUse> attributes,
      List<String> modifiers,
      String doc) {
    List<FieldDecl> constants = new ArrayList<>();
    if (ctx.enum_body() != null) {
      for (CSharpParser.Enum_member_declarationContext m : ctx.enum_body().enum_member_declaration()) {
        String value = m.expression() == null ? null : textOf(m.expression());
        constants.add(
            new FieldDecl(
                m.identifier().getText(),
                TypeRef.of("int"),
                List.of(),
                toAttributes(m.attributes()),
                value));
      }
    }
    return new TypeDecl(
        TypeKind.ENUM,
        ctx.identifier().getText(),
        modifiers,
        List.of(),
        attributes,
        constants,
        List.of(),
        List.of(),
        List.of(),
        doc,
        ClassRole.UNKNOWN);
  }

  private List<TypeRef> baseTypes(CSharpParser.Class_baseContext ctx) {
    List<TypeRef> bases = new ArrayList<>();
    if (ctx == null) {
      return bases;
    }
    if (ctx.class_type() != null) {
      bases.add(classTypeRef(ctx.class_type()));
    }
    for (CSharpParser.Namespace_or_type_nameContext n : ctx.namespace_or_type_name()) {
      bases.add(toTypeRef(n));
    }
    return bases;
  }

  // -------------------------------------------------------------- members

  private static final class Members {
    private final List<FieldDecl> fields = new ArrayList<>();
    private final List<PropertyDecl> properties = new ArrayList<>();
    private final List<MethodDecl> methods = new ArrayList<>();
    private final List<ConstructorDecl> constructors = new ArrayList<>();
  }

  private void collectClassMembers(
      CSharpParser.Class_member_declarationsContext ctx, Members out) {
    for (CSharpParser.Class_member_declarationContext m : ctx.class_member_declaration()) {
      if (m.common_member_declaration() == null) {
        continue;
      }
      collectCommonMember(
          m.common_member_declaration(),
          toAttributes(m.attributes()),
          toModifiers(m.all_member_modifiers()),
          m,
          out);
    }
  }

  private void collectCommonMember(
      CSharpParser.Common_member_declarationContext ctx,
      List<AttributeUse> attributes,
      List<String> modifiers,
      ParserRuleContext declaration,
      Members out) {

    String doc = SourceText.docCommentAbove(tokens, declaration);

    if (ctx.constructor_declaration() != null) {
      CSharpParser.Constructor_declarationContext c = ctx.constructor_declaration();
      out.constructors.add(
          new ConstructorDecl(
              toParams(c.formal_parameter_list()), textOf(c.body()), modifiers));
      return;
    }

    if (ctx.VOID() != null && ctx.method_declaration() != null) {
      out.methods.add(
          toMethod(ctx.method_declaration(), TypeRef.of("void"), attributes, modifiers, doc));
      return;
    }

    CSharpParser.Typed_member_declarationContext typed = ctx.typed_member_declaration();
    if (typed == null) {
      return;
    }
    TypeRef type = toTypeRef(typed.type_());

    if (typed.method_declaration() != null) {
      out.methods.add(toMethod(typed.method_declaration(), type, attributes, modifiers, doc));
    } else if (typed.property_declaration() != null) {
      out.properties.add(
          toProperty(typed.property_declaration(), type, attributes, modifiers, doc));
    } else if (typed.field_declaration() != null) {
      for (CSharpParser.Variable_declaratorContext v :
          typed.field_declaration().variable_declarators().variable_declarator()) {
        String init = v.variable_initializer() == null ? null : textOf(v.variable_initializer());
        out.fields.add(
            new FieldDecl(v.identifier().getText(), type, modifiers, attributes, init));
      }
    }
    // Indexers and operators have no clean Spring equivalent and are left out of
    // the IR entirely; the classifier flags the type for manual review.
  }

  private MethodDecl toMethod(
      CSharpParser.Method_declarationContext ctx,
      TypeRef returnType,
      List<AttributeUse> attributes,
      List<String> modifiers,
      String doc) {

    String body = null;
    if (ctx.method_body() != null && ctx.method_body().block() != null) {
      body = textOf(ctx.method_body().block());
    } else if (ctx.throwable_expression() != null) {
      // Expression-bodied method: wrapped so downstream sees a normal block.
      body = "{ return " + textOf(ctx.throwable_expression()) + "; }";
    }

    return new MethodDecl(
        lastIdentifier(ctx.method_member_name()),
        returnType,
        toParams(ctx.formal_parameter_list()),
        modifiers,
        attributes,
        body,
        ctx.getStart().getLine(),
        ctx.getStop().getLine(),
        doc);
  }

  private PropertyDecl toProperty(
      CSharpParser.Property_declarationContext ctx,
      TypeRef type,
      List<AttributeUse> attributes,
      List<String> modifiers,
      String doc) {

    boolean hasGetter = false;
    boolean hasSetter = false;
    String initializer = null;

    CSharpParser.Accessor_declarationsContext accessors = ctx.accessor_declarations();
    if (accessors != null) {
      hasGetter = accessors.GET() != null || accessors.get_accessor_declaration() != null;
      hasSetter = accessors.SET() != null || accessors.set_accessor_declaration() != null;
      if (ctx.variable_initializer() != null) {
        initializer = textOf(ctx.variable_initializer());
      }
    } else if (ctx.throwable_expression() != null) {
      // Expression-bodied property: read-only, and the expression is a body rather
      // than an initial value. Kept as the initializer so the generator can decide.
      hasGetter = true;
      initializer = textOf(ctx.throwable_expression());
    }

    return new PropertyDecl(
        lastIdentifier(ctx.member_name()),
        type,
        modifiers,
        attributes,
        hasGetter,
        hasSetter,
        initializer,
        doc);
  }

  private List<ParamDecl> toParams(CSharpParser.Formal_parameter_listContext ctx) {
    List<ParamDecl> params = new ArrayList<>();
    if (ctx == null || ctx.fixed_parameters() == null) {
      return params;
    }
    for (CSharpParser.Fixed_parameterContext p : ctx.fixed_parameters().fixed_parameter()) {
      CSharpParser.Arg_declarationContext arg = p.arg_declaration();
      if (arg == null) {
        continue;
      }
      params.add(
          new ParamDecl(
              arg.identifier().getText(),
              toTypeRef(arg.type_()),
              toAttributes(p.attributes()),
              arg.expression() == null ? null : textOf(arg.expression())));
    }
    return params;
  }

  // ----------------------------------------------------------- attributes

  private List<AttributeUse> toAttributes(CSharpParser.AttributesContext ctx) {
    List<AttributeUse> uses = new ArrayList<>();
    if (ctx == null) {
      return uses;
    }
    for (CSharpParser.Attribute_sectionContext section : ctx.attribute_section()) {
      for (CSharpParser.AttributeContext attr : section.attribute_list().attribute()) {
        uses.add(toAttribute(attr));
      }
    }
    return uses;
  }

  private AttributeUse toAttribute(CSharpParser.AttributeContext ctx) {
    String name = lastIdentifier(ctx.namespace_or_type_name());
    // C# allows [Required] and [RequiredAttribute] interchangeably.
    if (name.endsWith("Attribute") && name.length() > "Attribute".length()) {
      name = name.substring(0, name.length() - "Attribute".length());
    }

    List<String> positional = new ArrayList<>();
    Map<String, String> named = new LinkedHashMap<>();

    for (CSharpParser.Attribute_argumentContext arg : ctx.attribute_argument()) {
      String text = textOf(arg);
      if (arg.identifier() != null) {
        // Named argument in colon form: Name: value
        named.put(arg.identifier().getText(), textOf(arg.expression()));
        continue;
      }
      // The grammar parses `MinimumLength = 1` as an assignment expression, so
      // the usual C# named-argument form has to be split out by hand.
      int eq = topLevelAssignment(text);
      if (eq > 0) {
        named.put(text.substring(0, eq).strip(), text.substring(eq + 1).strip());
      } else {
        positional.add(text.strip());
      }
    }
    return new AttributeUse(name, positional, named);
  }

  /**
   * Index of an assignment {@code =} that is not part of {@code ==}, {@code >=}, {@code <=}, {@code
   * !=} or a lambda {@code =>}, and is outside quotes, brackets and parentheses. Returns -1 when
   * there is none.
   */
  private static int topLevelAssignment(String text) {
    int depth = 0;
    boolean inString = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
        inString = !inString;
      } else if (!inString) {
        if (c == '(' || c == '[' || c == '{') {
          depth++;
        } else if (c == ')' || c == ']' || c == '}') {
          depth--;
        } else if (c == '=' && depth == 0) {
          char prev = i > 0 ? text.charAt(i - 1) : ' ';
          char next = i + 1 < text.length() ? text.charAt(i + 1) : ' ';
          boolean partOfComparison =
              next == '=' || prev == '=' || prev == '!' || prev == '<' || prev == '>';
          boolean lambdaArrow = next == '>';
          if (!partOfComparison && !lambdaArrow) {
            return i;
          }
        }
      }
    }
    return -1;
  }

  // ---------------------------------------------------------------- types

  private TypeRef toTypeRef(CSharpParser.Type_Context ctx) {
    if (ctx == null) {
      return TypeRef.of("var");
    }
    TypeRef base = baseTypeRef(ctx.base_type());

    boolean nullable = false;
    int rank = 0;
    for (ParseTree child : children(ctx)) {
      if (child instanceof TerminalNode t && "?".equals(t.getText())) {
        nullable = true;
      } else if (child instanceof CSharpParser.Rank_specifierContext) {
        rank++;
      }
    }
    return new TypeRef(base.name(), base.typeArgs(), nullable, rank);
  }

  private TypeRef baseTypeRef(CSharpParser.Base_typeContext ctx) {
    if (ctx == null) {
      return TypeRef.of("var");
    }
    if (ctx.class_type() != null) {
      return classTypeRef(ctx.class_type());
    }
    if (ctx.simple_type() != null) {
      return TypeRef.of(ctx.simple_type().getText());
    }
    if (ctx.tuple_type() != null) {
      // Tuples have no Java equivalent worth guessing at; the name survives so the
      // generator can raise an UNSUPPORTED_CONSTRUCT finding against it.
      return TypeRef.of("ValueTuple");
    }
    return TypeRef.of(ctx.getText());
  }

  private TypeRef classTypeRef(CSharpParser.Class_typeContext ctx) {
    if (ctx.namespace_or_type_name() != null) {
      return toTypeRef(ctx.namespace_or_type_name());
    }
    // OBJECT, DYNAMIC or STRING keyword
    return TypeRef.of(ctx.getText());
  }

  /**
   * Collapses a qualified name onto its final segment: {@code System.Collections.Generic.List<Book>}
   * becomes {@code List<Book>}. The type mapper works from simple names, and C# using directives
   * mean the qualified form is the exception rather than the rule.
   */
  private TypeRef toTypeRef(CSharpParser.Namespace_or_type_nameContext ctx) {
    List<CSharpParser.IdentifierContext> ids = ctx.identifier();
    String name = ids.isEmpty() ? ctx.getText() : ids.get(ids.size() - 1).getText();

    List<CSharpParser.Type_argument_listContext> argLists = ctx.type_argument_list();
    List<TypeRef> args = new ArrayList<>();
    if (!argLists.isEmpty()) {
      for (CSharpParser.Type_Context t : argLists.get(argLists.size() - 1).type_()) {
        args.add(toTypeRef(t));
      }
    }
    return new TypeRef(name, args, false, 0);
  }

  // --------------------------------------------------------------- shared

  private List<String> toModifiers(CSharpParser.All_member_modifiersContext ctx) {
    List<String> modifiers = new ArrayList<>();
    if (ctx == null) {
      return modifiers;
    }
    for (CSharpParser.All_member_modifierContext m : ctx.all_member_modifier()) {
      modifiers.add(m.getText());
    }
    return modifiers;
  }

  private static String lastIdentifier(ParserRuleContext ctx) {
    String text = ctx.getText();
    int generic = text.indexOf('<');
    if (generic >= 0) {
      text = text.substring(0, generic);
    }
    int dot = text.lastIndexOf('.');
    return dot >= 0 ? text.substring(dot + 1) : text;
  }

  private static List<ParseTree> children(ParserRuleContext ctx) {
    List<ParseTree> out = new ArrayList<>();
    for (int i = 0; i < ctx.getChildCount(); i++) {
      out.add(ctx.getChild(i));
    }
    return out;
  }

  private static String textOf(ParserRuleContext ctx) {
    return SourceText.of(ctx);
  }
}
