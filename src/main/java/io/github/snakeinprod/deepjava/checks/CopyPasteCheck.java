package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.BlockTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.ExpressionStatementTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.VariableTree;

/** Next to {@code a.x - b.x}, the expression {@code a.y - b.x} probably meant {@code b.y}. */
@Rule(key = CopyPasteCheck.KEY)
public class CopyPasteCheck extends DeepCheck {

  public static final String KEY = "CopyPasteIdentifier";

  public CopyPasteCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.BLOCK, Tree.Kind.METHOD_INVOCATION);
  }

  /** {@code left.lp OP right.rp}. */
  private record Pair(BinaryExpressionTree tree, Symbol left, String lp, Symbol right, String rp, ExpressionTree rightOperand,
    ExpressionTree leftOperand) {
  }

  /** One {@code target.prop(source.getSrcProp())} step of a setter sequence or builder chain. */
  private record Copy(MethodInvocationTree call, String prop, Symbol source, String srcProp) {
  }

  @Override
  public void visitNode(Tree tree) {
    List<Copy> copies = new ArrayList<>();
    if (tree instanceof BlockTree block) {
      for (StatementTree st : block.body()) {
        copies.add(st instanceof ExpressionStatementTree es && Trees.skip(es.expression()) instanceof MethodInvocationTree m ? copy(m) : null);
      }
    } else if (!(tree.parent() instanceof MemberSelectExpressionTree)) {
      // the outermost call of a builder chain: walk it from the first step
      List<Copy> chain = new ArrayList<>();
      for (ExpressionTree e = (MethodInvocationTree) tree; e instanceof MethodInvocationTree m; e = Trees.receiver(m)) {
        chain.add(0, copy(m));
      }
      copies.addAll(chain);
    }
    for (int i = 1; i < copies.size(); i++) {
      Copy ref = copies.get(i - 1);
      Copy c = copies.get(i);
      if (ref != null && c != null && ref.prop().equals(ref.srcProp()) && ref.source().equals(c.source())
        && c.srcProp().equals(ref.srcProp()) && !c.prop().equals(c.srcProp())) {
        reportIssue(c.call().arguments().get(0), "Value \"" + ref.srcProp() + "\" is copied into \"" + c.prop()
          + "\" as in the line above; it might be \"" + c.prop() + "\".");
      }
    }
    pairs(tree);
  }

  /** {@code x.setFirst(a.getFirst())} or {@code builder.first(a.getFirst())} as (first, a, first). */
  private static Copy copy(MethodInvocationTree m) {
    if (m.arguments().size() != 1 || !(Trees.skip(m.arguments().get(0)) instanceof MethodInvocationTree g) || !g.arguments().isEmpty()) {
      return null;
    }
    ExpressionTree src = Trees.receiver(g);
    Symbol s = src == null ? null : Trees.symbol(src);
    String getter = Trees.name(g);
    if (s == null || !getter.matches("(get|is)[A-Z].*")) {
      return null;
    }
    String prop = Trees.name(m).replaceFirst("^(set|with)(?=[A-Z])", "");
    return new Copy(m, decap(prop), s, decap(getter.replaceFirst("^(get|is)", "")));
  }

  private static String decap(String s) {
    return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
  }

  private void pairs(Tree tree) {
    List<ExpressionTree> exprs = new ArrayList<>();
    if (tree instanceof BlockTree block) {
      for (StatementTree s : block.body()) {
        ExpressionTree e = switch (s) {
          case VariableTree v -> v.initializer();
          case ExpressionStatementTree es when es.expression() instanceof AssignmentExpressionTree a -> a.expression();
          default -> null;
        };
        exprs.add(e);
      }
    } else {
      exprs.addAll(((MethodInvocationTree) tree).arguments());
    }
    List<Pair> pairs = new ArrayList<>();
    for (ExpressionTree e : exprs) {
      pairs.add(e == null ? null : pair(Trees.skip(e)));
    }
    for (int i = 1; i < pairs.size(); i++) {
      Pair ref = pairs.get(i - 1);
      Pair p = pairs.get(i);
      if (ref == null || p == null || !ref.lp().equals(ref.rp()) || !ref.tree().operatorToken().text().equals(p.tree().operatorToken().text())
        || !ref.left().equals(p.left()) || !ref.right().equals(p.right())) {
        continue;
      }
      if (!p.lp().equals(p.rp()) && p.rp().equals(ref.rp()) && !p.lp().equals(ref.lp())) {
        reportIssue(p.rightOperand(), "\"" + p.right().name() + "." + p.rp() + "\" looks copied from the line above; did you mean \""
          + p.right().name() + "." + p.lp() + "\"?");
      } else if (!p.lp().equals(p.rp()) && p.lp().equals(ref.lp()) && !p.rp().equals(ref.rp())) {
        reportIssue(p.leftOperand(), "\"" + p.left().name() + "." + p.lp() + "\" looks copied from the line above; did you mean \""
          + p.left().name() + "." + p.rp() + "\"?");
      }
    }
  }

  private static Pair pair(ExpressionTree e) {
    if (!(e instanceof BinaryExpressionTree b) || !b.is(Tree.Kind.MINUS, Tree.Kind.PLUS, Tree.Kind.MULTIPLY, Tree.Kind.DIVIDE,
      Tree.Kind.LESS_THAN, Tree.Kind.GREATER_THAN, Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO)) {
      return null;
    }
    Object[] l = member(b.leftOperand());
    Object[] r = member(b.rightOperand());
    if (l == null || r == null || l[0].equals(r[0])) {
      return null;
    }
    return new Pair(b, (Symbol) l[0], (String) l[1], (Symbol) r[0], (String) r[1], b.rightOperand(), b.leftOperand());
  }

  /** {@code a.x} or {@code a.getX()} as {owner symbol, property}. */
  private static Object[] member(ExpressionTree e) {
    e = Trees.skip(e);
    if (e instanceof MethodInvocationTree m && m.arguments().isEmpty()) {
      e = m.methodSelect();
    }
    if (e instanceof MemberSelectExpressionTree ms && ms.expression() instanceof IdentifierTree id && !id.symbol().isUnknown()
      && id.symbol().isVariableSymbol()) {
      return new Object[] {id.symbol(), ms.identifier().name()};
    }
    return null;
  }
}
