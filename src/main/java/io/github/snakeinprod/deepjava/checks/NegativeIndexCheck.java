package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.ArrayAccessExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;
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
    if (search == null) {
      return;
    }
    Tree parent = mit.parent();
    String message = "\"" + search + "\" returns -1 when nothing is found; check the result before using it as an index.";
    if (isIndexUse(mit, parent)) {
      reportIssue(mit, message);
    } else if (parent instanceof VariableTree v && Trees.isLocal(v.symbol())) {
      List<IdentifierTree> usages = new ArrayList<>(v.symbol().usages());
      if (usages.isEmpty() || usages.stream().anyMatch(u -> u.parent() instanceof org.sonar.plugins.java.api.tree.AssignmentExpressionTree a && a.variable() == u)) {
        return;
      }
      usages.sort((a, b) -> a.identifierToken().range().start().compareTo(b.identifierToken().range().start()));
      IdentifierTree first = usages.get(0);
      if (isIndexUse(first, first.parent())) {
        reportIssue(first, message);
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
    if (!targets.isEmpty() && name.matches("(?i).*(index|find|search|position|pos|lookup).*")
      && targets.stream().allMatch(t -> t.facts().nulls().mayReturnMinusOne())) {
      return targets.get(0).display();
    }
    return null;
  }

  private static boolean isIndexUse(ExpressionTree value, Tree parent) {
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
