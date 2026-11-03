package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.LambdaExpressionTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.VariableTree;

/** A project method returns a new resource, and the caller neither closes it nor hands it on. */
@Rule(key = ResourceFromHelperCheck.KEY)
public class ResourceFromHelperCheck extends DeepCheck {

  public static final String KEY = "ResourceFromHelperNotClosed";

  public ResourceFromHelperCheck(Supplier<Summaries> summaries) {
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
    if (sym.isUnknown() || ROOTS.stream().noneMatch(r -> sym.returnType().type().isSubtypeOf(r.replace('/', '.').replace('$', '.')))
      || !isRealResource(sym.returnType().type().fullyQualifiedName())) {
      return;
    }
    String key = Trees.callKey(sym);
    Summaries s = summaries.get();
    List<Summaries.Method> targets = key == null ? List.of() : s.targets(key);
    if (targets.isEmpty() || !targets.stream().allMatch(t -> fresh(s, t, 0))) {
      return;
    }
    String type = sym.returnType().type().name();
    String message = "\"" + targets.get(0).display() + "\" returns a new \"" + type + "\" that is never closed here.";
    Tree parent = mit.parent();
    while (parent != null && parent.is(Tree.Kind.PARENTHESIZED_EXPRESSION, Tree.Kind.TYPE_CAST)) {
      parent = parent.parent();
    }
    if (parent == null) {
      return;
    }
    if (parent.is(Tree.Kind.EXPRESSION_STATEMENT)) {
      reportIssue(mit, message);
    } else if (parent instanceof MemberSelectExpressionTree ms && !"close".equals(ms.identifier().name())
      && ms.parent() instanceof MethodInvocationTree call && !sym.returnType().type().equals(call.methodSymbol().returnType().type())) {
      reportIssue(mit, message);
    } else if (parent instanceof VariableTree v && Trees.isLocal(v.symbol()) && !v.parent().is(Tree.Kind.LIST) && neverReleased(v.symbol())) {
      reportIssue(v.simpleName(), message);
    }
  }

  static boolean fresh(Summaries s, Summaries.Method t, int depth) {
    for (String type : t.facts().nulls().returnedNew()) {
      if (ROOTS.stream().anyMatch(r -> s.isSubtype(type, r)) && isRealResource(type)) {
        return true;
      }
    }
    for (String call : t.facts().nulls().returnedCalls().keySet()) {
      Summaries.Call c = Summaries.parse(call);
      if (s.models().isResourceFactory(c.owner(), c.name(), s.hierarchy())) {
        return true;
      }
      if (depth < 3 && s.targets(call).stream().anyMatch(x -> fresh(s, x, depth + 1))) {
        return true;
      }
    }
    return false;
  }

  /** Types that hold an operating-system or database resource. */
  static final List<String> ROOTS = List.of("java/io/InputStream", "java/io/OutputStream", "java/io/Reader", "java/io/Writer",
    "java/nio/channels/Channel", "java/net/Socket", "java/net/ServerSocket", "java/sql/Connection", "java/sql/Statement", "java/sql/ResultSet",
    "java/util/zip/ZipFile", "java/io/RandomAccessFile", "java/nio/file/FileSystem", "java/nio/file/DirectoryStream", "java/nio/file/WatchService");

  /** Buffers in memory need no close(). */
  static boolean isRealResource(String type) {
    return !type.matches("(?i).*(ByteArray|CharArray|StringReader|StringWriter|StringBuilderWriter|Formatter$).*");
  }

  /** Not closed, returned, stored, passed on, captured by a lambda or used in try-with-resources. */
  private static boolean neverReleased(Symbol var) {
    for (IdentifierTree u : var.usages()) {
      Tree p = u.parent();
      while (p != null && p.is(Tree.Kind.PARENTHESIZED_EXPRESSION, Tree.Kind.TYPE_CAST)) {
        p = p.parent();
      }
      if (p == null) {
        return false;
      }
      if (p instanceof MemberSelectExpressionTree ms) {
        if ("close".equals(ms.identifier().name())) {
          return false;
        }
        continue;
      }
      if (p instanceof AssignmentExpressionTree a && a.variable() == u) {
        return false;
      }
      // argument, return, assignment value, array element, resource of try (r), condition...
      if (!p.is(Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO, Tree.Kind.EXPRESSION_STATEMENT)) {
        return false;
      }
    }
    for (IdentifierTree u : var.usages()) {
      for (Tree p = u.parent(); p != null; p = p.parent()) {
        if (p instanceof LambdaExpressionTree) {
          return false;
        }
      }
    }
    return true;
  }
}
