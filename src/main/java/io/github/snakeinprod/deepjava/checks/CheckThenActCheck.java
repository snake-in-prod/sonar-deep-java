package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IfStatementTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.Modifier;
import org.sonar.plugins.java.api.tree.SynchronizedStatementTree;
import org.sonar.plugins.java.api.tree.Tree;

/** {@code if (!map.containsKey(k)) { synchronized (lock) { map.put(...); } }} without checking again inside. */
@Rule(key = CheckThenActCheck.KEY)
public class CheckThenActCheck extends DeepCheck {

  public static final String KEY = "CheckThenActOutsideLock";

  private static final Set<String> MUTATORS = Set.of("put", "putAll", "putIfAbsent", "add", "addAll", "remove", "removeAll", "clear", "set",
    "compute", "computeIfAbsent", "computeIfPresent", "merge", "offer", "push", "poll", "replace");

  public CheckThenActCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.IF_STATEMENT);
  }

  @Override
  public void visitNode(Tree tree) {
    IfStatementTree ifs = (IfStatementTree) tree;
    if (insideLock(ifs)) {
      return;
    }
    Set<Symbol> fields = new HashSet<>();
    for (ExpressionTree e : Trees.collect(ifs.condition(), ExpressionTree.class)) {
      Symbol s = Trees.symbol(e);
      if (Trees.isField(s) && !(s.isStatic() && s.isFinal() && s.type().isPrimitive())) {
        fields.add(s);
      }
    }
    if (fields.isEmpty()) {
      return;
    }
    for (SynchronizedStatementTree sync : Trees.collect(ifs.thenStatement(), SynchronizedStatementTree.class)) {
      for (Symbol f : fields) {
        boolean rechecked = Trees.collect(sync.block(), IfStatementTree.class).stream()
          .anyMatch(inner -> Trees.collect(inner.condition(), ExpressionTree.class).stream().anyMatch(e -> f.equals(Trees.symbol(e))));
        if (!rechecked && writes(sync, f)) {
          reportIssue(ifs.condition(), "\"" + f.name() + "\" is checked outside the lock and changed inside it at line "
            + Trees.line(sync) + " without checking again.");
          return;
        }
      }
    }
  }

  private static boolean insideLock(Tree t) {
    for (Tree p = t.parent(); p != null; p = p.parent()) {
      if (p instanceof SynchronizedStatementTree) {
        return true;
      }
      if (p instanceof MethodTree m) {
        return m.modifiers().modifiers().stream().anyMatch(mk -> mk.modifier() == Modifier.SYNCHRONIZED);
      }
    }
    return false;
  }

  private static boolean writes(SynchronizedStatementTree sync, Symbol f) {
    return Trees.any(sync.block(), AssignmentExpressionTree.class, a -> f.equals(Trees.symbol(a.variable())))
      || Trees.any(sync.block(), MethodInvocationTree.class, m -> Trees.receiver(m) != null && f.equals(Trees.symbol(Trees.receiver(m)))
        && MUTATORS.contains(Trees.name(m)));
  }
}
