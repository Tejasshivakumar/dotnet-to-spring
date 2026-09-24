package com.portway.core.parse;

import com.portway.core.parse.grammar.CSharpLexer;
import com.portway.core.parse.grammar.CSharpParser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * Rejects constructs the grammar accepts but the IR cannot represent faithfully.
 *
 * <p>The vendored grammar parses far more C# than Portway can translate. Without this pass, a
 * pointer field parses cleanly and quietly becomes an {@code int}, and a nested class vanishes
 * without a trace. A file that uses one of these constructs is rejected with the construct named
 * and its line, and the rest of the project still migrates.
 *
 * <p>Rejection is deliberately loud. Plausible-looking Java generated from a half-understood file is
 * worse than a clear error, because nobody knows to review it.
 */
public final class SubsetValidator {

  private SubsetValidator() {}

  /** Every unsupported construct in the tree, one error per occurrence. */
  public static List<SyntaxError> validate(CSharpParser.Compilation_unitContext tree, String path) {
    Collector collector = new Collector(path);
    ParseTreeWalker.DEFAULT.walk(collector, tree);
    if (collector.namespacesWithTypes.size() > 1) {
      collector.errors.add(
          new SyntaxError(
              path,
              collector.secondNamespaceLine,
              0,
              "Unsupported construct: types declared in more than one namespace in one file ("
                  + String.join(", ", collector.namespacesWithTypes)
                  + "). Split the file, one namespace per file."));
    }
    return collector.errors;
  }

  private static final class Collector implements ParseTreeListener {

    private final String path;
    private final List<SyntaxError> errors = new ArrayList<>();
    private final Set<String> namespacesWithTypes = new LinkedHashSet<>();
    private int secondNamespaceLine;

    Collector(String path) {
      this.path = path;
    }

    @Override
    public void enterEveryRule(ParserRuleContext ctx) {
      if (ctx instanceof CSharpParser.Query_expressionContext) {
        reject(ctx, "LINQ query syntax (from ... select). Use method syntax: .Where(), .Select()");
      } else if (ctx instanceof CSharpParser.Pointer_typeContext) {
        reject(ctx, "pointer type");
      } else if (ctx instanceof CSharpParser.Type_Context type && hasStar(type)) {
        reject(ctx, "pointer type");
      } else if (ctx instanceof CSharpParser.Common_member_declarationContext member) {
        checkMember(member);
      } else if (ctx instanceof CSharpParser.Typed_member_declarationContext typed) {
        if (typed.indexer_declaration() != null) {
          reject(ctx, "indexer (this[...])");
        } else if (typed.operator_declaration() != null) {
          reject(ctx, "operator overload");
        }
      } else if (ctx instanceof CSharpParser.Destructor_definitionContext) {
        reject(ctx, "finalizer (~ClassName)");
      } else if (ctx instanceof CSharpParser.Namespace_member_declarationContext member
          && member.type_declaration() != null) {
        recordNamespace(member);
      }
    }

    /**
     * Nested types are rejected rather than hoisted. Hoisting changes the type's name and its
     * access to the outer class's private members, and both are silent semantic changes.
     */
    private void checkMember(CSharpParser.Common_member_declarationContext member) {
      boolean inStruct = member.getParent() instanceof CSharpParser.Struct_member_declarationContext;
      boolean inClass = member.getParent() instanceof CSharpParser.Class_member_declarationContext;
      if (!inStruct && !inClass) {
        return;
      }
      if (member.class_definition() != null
          || member.struct_definition() != null
          || member.interface_definition() != null
          || member.enum_definition() != null
          || member.delegate_definition() != null) {
        reject(member, "nested type declaration. Move it to its own file");
      } else if (member.event_declaration() != null) {
        reject(member, "event declaration");
      } else if (member.conversion_operator_declarator() != null) {
        reject(member, "conversion operator");
      }
    }

    private void recordNamespace(CSharpParser.Namespace_member_declarationContext member) {
      String namespace = enclosingNamespace(member);
      if (namespacesWithTypes.add(namespace) && namespacesWithTypes.size() == 2) {
        secondNamespaceLine = member.getStart().getLine();
      }
    }

    private static String enclosingNamespace(ParserRuleContext ctx) {
      List<String> parts = new ArrayList<>();
      for (ParserRuleContext p = ctx.getParent(); p != null; p = p.getParent()) {
        if (p instanceof CSharpParser.Block_scoped_namespaceContext block) {
          parts.add(0, block.qualified_identifier().getText());
        } else if (p instanceof CSharpParser.File_scoped_namespaceContext file) {
          parts.add(0, file.qualified_identifier().getText());
        }
      }
      return parts.isEmpty() ? "<global>" : String.join(".", parts);
    }

    private static boolean hasStar(CSharpParser.Type_Context type) {
      for (int i = 0; i < type.getChildCount(); i++) {
        if (type.getChild(i) instanceof TerminalNode t && "*".equals(t.getText())) {
          return true;
        }
      }
      return false;
    }

    @Override
    public void visitTerminal(TerminalNode node) {
      Token token = node.getSymbol();
      switch (token.getType()) {
        case CSharpLexer.UNSAFE -> reject(token, "unsafe code");
        case CSharpLexer.FIXED -> reject(token, "fixed statement or fixed-size buffer");
        case CSharpLexer.STACKALLOC -> reject(token, "stackalloc");
        default -> {
          // supported
        }
      }
    }

    private void reject(ParserRuleContext ctx, String construct) {
      reject(ctx.getStart(), construct);
    }

    private void reject(Token token, String construct) {
      SyntaxError error =
          new SyntaxError(
              path,
              token.getLine(),
              token.getCharPositionInLine(),
              "Unsupported construct: " + construct);
      // A pointer type is reported once by Type_ and again by its Pointer_type child.
      if (!errors.contains(error)) {
        errors.add(error);
      }
    }

    @Override
    public void visitErrorNode(ErrorNode node) {}

    @Override
    public void exitEveryRule(ParserRuleContext ctx) {}
  }
}
