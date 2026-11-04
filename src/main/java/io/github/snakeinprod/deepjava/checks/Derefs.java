package io.github.snakeinprod.deepjava.checks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.semantic.Type;
import org.sonar.plugins.java.api.tree.ArrayAccessExpressionTree;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.CaseLabelTree;
import org.sonar.plugins.java.api.tree.ConditionalExpressionTree;
import org.sonar.plugins.java.api.tree.DoWhileStatementTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.ForEachStatement;
import org.sonar.plugins.java.api.tree.ForStatementTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.IfStatementTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.NewClassTree;
import org.sonar.plugins.java.api.tree.ReturnStatementTree;
import org.sonar.plugins.java.api.tree.SwitchTree;
import org.sonar.plugins.java.api.tree.SynchronizedStatementTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.UnaryExpressionTree;
import org.sonar.plugins.java.api.tree.VariableTree;
import org.sonar.plugins.java.api.tree.WhileStatementTree;

/** Where a value is dereferenced, explicitly ({@code v.m()}) or not ({@code switch (v)}, unboxing a {@code Long}). */
final class Derefs {

  private Derefs() {
  }

  /** Calls that test for null (or emptiness, which includes null) rather than dereference. */
  private static final Set<String> NULL_TESTS = Set.of("isNull", "nonNull", "requireNonNullElse", "requireNonNullElseGet",
    "ofNullable", "isEmpty", "isNotEmpty", "isBlank", "isNotBlank", "hasText", "hasLength", "firstNonNull", "defaultIfNull",
    "nullToEmpty", "isNullOrEmpty", "emptyIfNull", "defaultString", "notNull", "checkNotNull");

  /** Parent of {@code tree}, looking through parentheses and casts. */
  static Tree parent(Tree tree) {
    Tree p = tree.parent();
    while (p != null && (p.is(Tree.Kind.PARENTHESIZED_EXPRESSION) || p.is(Tree.Kind.TYPE_CAST))) {
      p = p.parent();
    }
    return p;
  }

  /** {@code value} (whose type is {@code boxed} when a wrapper such as {@code Long}) is dereferenced by {@code parent}. */
  static boolean isDereference(Tree parent, ExpressionTree value, boolean boxed) {
    if (parent == null) {
      return false;
    }
    boolean explicit = switch (parent.kind()) {
      case MEMBER_SELECT -> is(((MemberSelectExpressionTree) parent).expression(), value);
      case ARRAY_ACCESS_EXPRESSION -> is(((ArrayAccessExpressionTree) parent).expression(), value);
      case FOR_EACH_STATEMENT -> is(((ForEachStatement) parent).expression(), value);
      case SYNCHRONIZED_STATEMENT -> is(((SynchronizedStatementTree) parent).expression(), value);
      case SWITCH_STATEMENT, SWITCH_EXPRESSION -> is(((SwitchTree) parent).expression(), value) && !hasNullCase((SwitchTree) parent);
      default -> false;
    };
    return explicit || boxed && unboxes(parent, value);
  }

  static boolean isBoxed(Type t) {
    return t != null && !t.isUnknown() && t.isPrimitiveWrapper();
  }

  /** A wrapper value used where a primitive is needed: argument, assignment, arithmetic, comparison, condition. */
  private static boolean unboxes(Tree parent, ExpressionTree value) {
    switch (parent.kind()) {
      case ARGUMENTS:
        return primitiveParameter(parent, value);
      case VARIABLE:
        VariableTree v = (VariableTree) parent;
        return is(v.initializer(), value) && v.type().symbolType().isPrimitive();
      case ASSIGNMENT:
        AssignmentExpressionTree a = (AssignmentExpressionTree) parent;
        return is(a.expression(), value) && a.variable().symbolType().isPrimitive();
      case PLUS_ASSIGNMENT, MINUS_ASSIGNMENT, MULTIPLY_ASSIGNMENT, DIVIDE_ASSIGNMENT, REMAINDER_ASSIGNMENT:
        return !((AssignmentExpressionTree) parent).variable().symbolType().is("java.lang.String");
      case PLUS:
        return !((BinaryExpressionTree) parent).symbolType().is("java.lang.String");
      case MINUS, MULTIPLY, DIVIDE, REMAINDER, LESS_THAN, GREATER_THAN, LESS_THAN_OR_EQUAL_TO, GREATER_THAN_OR_EQUAL_TO, LEFT_SHIFT,
        RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT, CONDITIONAL_AND, CONDITIONAL_OR:
        return true;
      case AND, OR, XOR:
        return true;
      case EQUAL_TO, NOT_EQUAL_TO:
        BinaryExpressionTree b = (BinaryExpressionTree) parent;
        ExpressionTree other = is(b.leftOperand(), value) ? b.rightOperand() : b.leftOperand();
        return other.symbolType().isPrimitive() && !other.is(Tree.Kind.NULL_LITERAL);
      case LOGICAL_COMPLEMENT, UNARY_MINUS, UNARY_PLUS, BITWISE_COMPLEMENT, PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT,
        POSTFIX_DECREMENT:
        return true;
      case IF_STATEMENT:
        return is(((IfStatementTree) parent).condition(), value);
      case WHILE_STATEMENT:
        return is(((WhileStatementTree) parent).condition(), value);
      case DO_STATEMENT:
        return is(((DoWhileStatementTree) parent).condition(), value);
      case FOR_STATEMENT:
        return is(((ForStatementTree) parent).condition(), value);
      case CONDITIONAL_EXPRESSION:
        return is(((ConditionalExpressionTree) parent).condition(), value);
      case ARRAY_DIMENSION:
        return true;
      case RETURN_STATEMENT:
        Tree m = Trees.enclosingMethod(parent);
        return m instanceof MethodTree mt && mt.returnType() != null && mt.returnType().symbolType().isPrimitive()
          && is(((ReturnStatementTree) parent).expression(), value);
      default:
        return false;
    }
  }

  private static boolean primitiveParameter(Tree arguments, ExpressionTree value) {
    Symbol.MethodSymbol sym = arguments.parent() instanceof MethodInvocationTree mit ? mit.methodSymbol()
      : arguments.parent() instanceof NewClassTree nc ? nc.methodSymbol() : null;
    if (sym == null || sym.isUnknown()) {
      return false;
    }
    @SuppressWarnings("unchecked")
    List<ExpressionTree> args = (List<ExpressionTree>) arguments;
    int i = -1;
    for (int k = 0; k < args.size(); k++) {
      if (is(args.get(k), value)) {
        i = k;
      }
    }
    List<Type> params = sym.parameterTypes();
    return i >= 0 && i < params.size() && !(sym.isVarArgsMethod() && i >= params.size() - 1) && params.get(i).isPrimitive();
  }

  private static boolean hasNullCase(SwitchTree s) {
    return s.cases().stream().flatMap(c -> c.labels().stream()).map(CaseLabelTree::expressions).flatMap(List::stream)
      .anyMatch(e -> e.is(Tree.Kind.NULL_LITERAL));
  }

  /** {@code outer} is {@code inner}, possibly wrapped in parentheses and casts. */
  static boolean is(ExpressionTree outer, ExpressionTree inner) {
    if (outer == null) {
      return false;
    }
    for (Tree t = inner; t != null; t = t.parent()) {
      if (t == outer) {
        return true;
      }
      if (t != inner && !t.is(Tree.Kind.PARENTHESIZED_EXPRESSION, Tree.Kind.TYPE_CAST)) {
        return false;
      }
    }
    return false;
  }

  /** The value is compared with null or handed to a null or emptiness test. */
  static boolean isNullTest(Tree parent, ExpressionTree value) {
    if (parent instanceof BinaryExpressionTree b && b.is(Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO)
      && (b.leftOperand().is(Tree.Kind.NULL_LITERAL) || b.rightOperand().is(Tree.Kind.NULL_LITERAL))) {
      return true;
    }
    if (parent != null && parent.is(Tree.Kind.INSTANCE_OF, Tree.Kind.PATTERN_INSTANCE_OF)) {
      return true;
    }
    return parent != null && parent.is(Tree.Kind.ARGUMENTS) && parent.parent() instanceof MethodInvocationTree call
      && NULL_TESTS.contains(Trees.name(call));
  }

  /**
   * The first dereference of a never-reassigned local, unless a null test comes first; uses in between (a log call,
   * passing it on) do not stop the search. In parity mode {@code requireNonNull(v)} counts as a dereference.
   */
  static IdentifierTree firstDereference(Symbol var, boolean parity) {
    List<IdentifierTree> usages = new ArrayList<>(var.usages());
    for (IdentifierTree u : usages) {
      if (u.parent() instanceof AssignmentExpressionTree a && a.variable() == u) {
        return null;
      }
    }
    usages.sort((a, b) -> a.identifierToken().range().start().compareTo(b.identifierToken().range().start()));
    boolean boxed = isBoxed(var.type());
    for (IdentifierTree u : usages) {
      Tree p = parent(u);
      if (isDereference(p, u, boxed) || parity && NullReturnDereferenceCheck.isRequireNonNullArgument(p, u)) {
        return u;
      }
      if (isNullTest(p, u) || p != null && p.is(Tree.Kind.ARGUMENTS) && p.parent() instanceof MethodInvocationTree call
        && "requireNonNull".equals(Trees.name(call))) {
        return null;
      }
    }
    return null;
  }
}
