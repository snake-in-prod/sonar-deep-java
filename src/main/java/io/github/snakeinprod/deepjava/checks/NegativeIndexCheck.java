package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.ArrayAccessExpressionTree;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.BlockTree;
import org.sonar.plugins.java.api.tree.ClassTree;
import org.sonar.plugins.java.api.tree.ConditionalExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.IfStatementTree;
import org.sonar.plugins.java.api.tree.LambdaExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.UnaryExpressionTree;
import org.sonar.plugins.java.api.tree.VariableTree;

/** {@code -1} from a search method used as an index without a check. */
@Rule(key = NegativeIndexCheck.KEY)
public class NegativeIndexCheck extends DeepCheck {

  public static final String KEY = "NegativeIndexFromSearch";

  private static final Set<String> INDEX_USERS = Set.of("charAt", "substring", "subSequence", "get", "remove", "set", "subList",
    "deleteCharAt", "codePointAt", "listIterator");

  public NegativeIndexCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.METHOD_INVOCATION);
  }

  @Override
  public void visitNode(Tree tree) {
    MethodInvocationTree mit = (MethodInvocationTree) tree;
    String search = search(mit);
    if (search == null || foundForSure(mit)) {
      return;
    }
    Tree parent = mit.parent();
    if (passedAsIndex(mit, parent)) {
      reportIssue(mit, "\"" + search + "\" returns -1 when nothing is found, and the called method uses this argument as an index.");
      return;
    }
    String message = "\"" + search + "\" returns -1 when nothing is found; check the result before using it as an index.";
    // parity: an index use in the same method is not a reference class, only one in the called method
    boolean parity = summaries.get().options().referenceParity();
    if (isIndexUse(mit, parent)) {
      if (!parity) {
        reportIssue(mit, message);
      }
    } else if (parent instanceof VariableTree v && Trees.isLocal(v.symbol())) {
      List<IdentifierTree> usages = new ArrayList<>(v.symbol().usages());
      if (usages.isEmpty() || usages.stream().anyMatch(u -> u.parent() instanceof org.sonar.plugins.java.api.tree.AssignmentExpressionTree a && a.variable() == u)) {
        return;
      }
      usages.sort((a, b) -> a.identifierToken().range().start().compareTo(b.identifierToken().range().start()));
      IdentifierTree first = usages.get(0);
      if (isIndexUse(first, first.parent())) {
        if (!parity) {
          reportIssue(first, message);
        }
      } else if (passedAsIndex(first, first.parent())) {
        reportIssue(first, "\"" + search + "\" returns -1 when nothing is found, and the called method uses this value as an index.");
      }
    }
  }

  private String search(MethodInvocationTree mit) {
    Symbol.MethodSymbol s = mit.methodSymbol();
    if (s.isUnknown() || !s.returnType().type().is("int")) {
      return null;
    }
    String name = s.name();
    if (Set.of("indexOf", "lastIndexOf").contains(name) && (s.owner().type().is("java.lang.String") || s.owner().type().isSubtypeOf("java.util.List")
      || s.owner().type().is("java.lang.StringBuilder"))) {
      return s.owner().name() + "." + name + "()";
    }
    if ("binarySearch".equals(name) && (s.owner().type().is("java.util.Arrays") || s.owner().type().is("java.util.Collections"))) {
      return s.owner().name() + ".binarySearch()";
    }
    String key = Trees.callKey(s);
    List<Summaries.Method> targets = key == null ? List.of() : summaries.get().targets(key);
    // any project method with a "return -1" path: "first free slot", "position of", "index of"
    if (!targets.isEmpty() && targets.stream().allMatch(t -> t.facts().nulls().mayReturnMinusOne())) {
      return targets.get(0).display();
    }
    return null;
  }

  private static final Set<String> PRESENCE = Set.of("contains", "startsWith", "endsWith");
  private static final Set<String> SEARCHES = Set.of("indexOf", "lastIndexOf");

  /**
   * {@code x.indexOf(y)} under an enclosing condition, or after an earlier guard that exits, which proves that x contains y.
   * ponytail: x and y are not checked for reassignment between the guard and the search.
   */
  private static boolean foundForSure(MethodInvocationTree search) {
    ExpressionTree x = Trees.receiver(search);
    if (x == null || search.arguments().size() != 1) {
      return false;
    }
    ExpressionTree y = search.arguments().get(0);
    for (Tree child = search, p = search.parent(); p != null && !(p instanceof MethodTree || p instanceof LambdaExpressionTree
      || p instanceof ClassTree); child = p, p = p.parent()) {
      if (p instanceof IfStatementTree i && child != i.condition() && implies(i.condition(), child == i.thenStatement(), x, y)
        || p instanceof ConditionalExpressionTree c && child != c.condition() && implies(c.condition(), child == c.trueExpression(), x, y)
        || p instanceof BinaryExpressionTree b && p.is(Tree.Kind.CONDITIONAL_AND, Tree.Kind.CONDITIONAL_OR) && child == b.rightOperand()
          && implies(b.leftOperand(), p.is(Tree.Kind.CONDITIONAL_AND), x, y)) {
        return true;
      }
      if (p instanceof BlockTree block) {
        for (StatementTree s : block.body()) {
          if (s == child) {
            break;
          }
          if (s instanceof IfStatementTree i && i.elseStatement() == null && exits(i.thenStatement()) && implies(i.condition(), false, x, y)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static boolean exits(StatementTree s) {
    if (s instanceof BlockTree b) {
      return !b.body().isEmpty() && exits(b.body().get(b.body().size() - 1));
    }
    return s.is(Tree.Kind.RETURN_STATEMENT, Tree.Kind.THROW_STATEMENT, Tree.Kind.CONTINUE_STATEMENT, Tree.Kind.BREAK_STATEMENT);
  }

  /** Whether {@code cond} evaluating to {@code value} proves that x contains y. */
  private static boolean implies(ExpressionTree cond, boolean value, ExpressionTree x, ExpressionTree y) {
    cond = Trees.skip(cond);
    if (cond.is(Tree.Kind.LOGICAL_COMPLEMENT)) {
      return implies(((UnaryExpressionTree) cond).expression(), !value, x, y);
    }
    if (cond instanceof BinaryExpressionTree b) {
      if (cond.is(value ? Tree.Kind.CONDITIONAL_AND : Tree.Kind.CONDITIONAL_OR)) {
        return implies(b.leftOperand(), value, x, y) || implies(b.rightOperand(), value, x, y);
      }
      if (!(Trees.skip(b.leftOperand()) instanceof MethodInvocationTree m && SEARCHES.contains(Trees.name(m)) && calledWith(m, x, y))) {
        return false;
      }
      Integer k = Trees.intConstant(b.rightOperand());
      // found: >= 0, != -1, > -1; not found: < 0, == -1, <= -1
      return k != null && (value
        ? cond.is(Tree.Kind.GREATER_THAN_OR_EQUAL_TO) && k == 0 || cond.is(Tree.Kind.NOT_EQUAL_TO, Tree.Kind.GREATER_THAN) && k == -1
        : cond.is(Tree.Kind.LESS_THAN) && k == 0 || cond.is(Tree.Kind.EQUAL_TO, Tree.Kind.LESS_THAN_OR_EQUAL_TO) && k == -1);
    }
    return value && cond instanceof MethodInvocationTree m && PRESENCE.contains(Trees.name(m))
      && (calledWith(m, x, y) || m.arguments().size() == 2 && Trees.same(m.arguments().get(0), x) && Trees.same(m.arguments().get(1), y));
  }

  /** {@code x.m(y)}. */
  private static boolean calledWith(MethodInvocationTree m, ExpressionTree x, ExpressionTree y) {
    ExpressionTree r = Trees.receiver(m);
    return r != null && m.arguments().size() == 1 && Trees.same(r, x) && Trees.same(m.arguments().get(0), y);
  }

  /** The value is an argument that the callee uses as an index without comparing it first. */
  private boolean passedAsIndex(ExpressionTree value, Tree parent) {
    if (parent == null || !parent.is(Tree.Kind.ARGUMENTS) || !(parent.parent() instanceof MethodInvocationTree call)) {
      return false;
    }
    int pos = call.arguments().indexOf(value);
    String key = Trees.resolveCallKey(call, summaries.get());
    List<Summaries.Method> targets = key == null ? List.of() : summaries.get().targets(key);
    return pos >= 0 && !targets.isEmpty() && targets.stream().allMatch(t -> summaries.get().indexesParameter(t, pos));
  }

  private static boolean isIndexUse(ExpressionTree value, Tree parent) {
    if (parent instanceof org.sonar.plugins.java.api.tree.ArrayDimensionTree) {
      parent = parent.parent();
    }
    if (parent instanceof ArrayAccessExpressionTree aa && aa.dimension().expression() == value) {
      return true;
    }
    if (parent != null && parent.is(Tree.Kind.ARGUMENTS) && parent.parent() instanceof MethodInvocationTree call) {
      String n = Trees.name(call);
      return INDEX_USERS.contains(n) && !call.methodSymbol().isUnknown() && call.methodSymbol().parameterTypes().stream().anyMatch(t -> t.is("int"))
        && (!"remove".equals(n) || call.methodSymbol().parameterTypes().get(0).is("int"));
    }
    return false;
  }
}
