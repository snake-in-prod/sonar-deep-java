package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;

/** {@code null}, or a value that can be null, passed to a parameter that the callee dereferences on every path. */
@Rule(key = NullArgumentCheck.KEY)
public class NullArgumentCheck extends DeepCheck {

  public static final String KEY = "NullPassedToDereferencedParameter";

  public NullArgumentCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.METHOD_INVOCATION);
  }

  @Override
  public void visitNode(Tree tree) {
    MethodInvocationTree mit = (MethodInvocationTree) tree;
    Symbol.MethodSymbol sym = mit.methodSymbol();
    if (!sym.isUnknown() && sym.isVarArgsMethod()) {
      return;
    }
    Summaries s = summaries.get();
    String key = Trees.resolveCallKey(mit, s);
    if (key == null) {
      return;
    }
    List<Summaries.Method> targets = s.targets(key);
    if (targets.isEmpty() || targets.size() > s.thresholds().maxTargets()) {
      return;
    }
    for (int i = 0; i < mit.arguments().size(); i++) {
      int index = i;
      boolean everyPath = targets.stream().allMatch(t -> t.facts().uses().derefParams().contains(index));
      boolean somePath = !everyPath && targets.stream().allMatch(t -> s.maybeDereferences(t, index));
      ExpressionTree arg = mit.arguments().get(i);
      boolean lombokField = s.options().referenceParity() && lombokNullCompared(s, Trees.skip(arg));
      if (!everyPath && !(somePath && (Trees.skip(arg).is(Tree.Kind.NULL_LITERAL) || lombokField))) {
        continue;
      }
      String why = nullWhy(s, arg);
      if (why != null) {
        List<String> names = targets.get(0).facts().info().paramNames();
        String param = i < names.size() && !names.get(i).isEmpty() ? "\"" + names.get(i) + "\"" : "#" + (i + 1);
        reportIssue(arg, "\"" + targets.get(0).display() + "\" dereferences its parameter " + param
          + (everyPath ? " on every path" : " without a null check") + ", but " + why + " is passed here.");
      }
    }
  }

  private static String nullWhy(Summaries s, ExpressionTree arg) {
    ExpressionTree a = Trees.skip(arg);
    if (a.is(Tree.Kind.NULL_LITERAL)) {
      return "null";
    }
    if (s.options().referenceParity() && lombokNullCompared(s, a)) {
      return "a field that the Lombok-generated equals() compares to null";
    }
    if (a instanceof MethodInvocationTree call) {
      String key = Trees.resolveCallKey(call, s);
      Optional<Summaries.NullReason> r = key == null ? Optional.empty() : s.nullReturn(key);
      if (r.isPresent() && r.get().guards().isEmpty() && s.isReportable(key, r.get())) {
        return "the result of \"" + r.get().method() + "\", which can be null (" + r.get().detail() + "),";
      }
    }
    return null;
  }

  /** A field (or its plain getter) of a class with a Lombok-generated {@code equals}, which null-checks every field. */
  private static boolean lombokNullCompared(Summaries s, ExpressionTree a) {
    String field = null;
    if (a instanceof MethodInvocationTree call && call.arguments().isEmpty()) {
      String key = Trees.resolveCallKey(call, s);
      List<Summaries.Method> targets = key == null ? List.of() : s.targets(key);
      if (targets.size() == 1 && targets.get(0).facts().nulls().returnedFields().size() == 1) {
        field = targets.get(0).facts().nulls().returnedFields().iterator().next();
      }
    } else {
      Symbol f = Trees.symbol(a);
      if (Trees.isField(f) && !f.type().isPrimitive() && !f.isStatic()) {
        field = f.owner().type().erasure().fullyQualifiedName().replace('.', '/') + "#" + f.name();
      }
    }
    if (field == null) {
      return false;
    }
    io.github.snakeinprod.deepjava.prepass.ClassFacts owner = s.classes().get(field.substring(0, field.indexOf('#')));
    return owner != null && owner.methods().containsKey("canEqual(Ljava/lang/Object;)Z")
      && owner.fields().containsKey(field.substring(field.indexOf('#') + 1));
  }
}
