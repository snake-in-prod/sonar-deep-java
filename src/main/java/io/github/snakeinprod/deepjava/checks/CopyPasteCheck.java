package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  /**
   * One {@code target.prop(source.getSrcProp())} step of a setter sequence or builder chain, or a local
   * {@code prop = source.getSrcProp()}; {@code value} is the getter call, {@code target} the receiver (or null).
   */
  private record Copy(ExpressionTree value, String prop, Symbol source, String srcProp, Symbol target) {
  }

  private static final String[][] TWINS = {{"min", "max"}, {"start", "end"}, {"first", "last"}, {"begin", "end"}, {"from", "to"},
    {"left", "right"}, {"top", "bottom"}, {"width", "height"}, {"lower", "upper"}, {"old", "new"}, {"src", "dst"}, {"source", "target"},
    {"in", "out"}, {"key", "value"}, {"x", "y"}};

  /** Names that differ from {@code name} by one twin word ({@code minWidth} and {@code maxWidth}). */
  static Set<String> twins(String name) {
    Set<String> out = new java.util.HashSet<>();
    for (String[] pair : TWINS) {
      for (int k = 0; k < 2; k++) {
        String a = pair[k];
        String b = pair[1 - k];
        String cap = Character.toUpperCase(a.charAt(0)) + a.substring(1);
        String capB = Character.toUpperCase(b.charAt(0)) + b.substring(1);
        if (name.startsWith(a) && (name.length() == a.length() || Character.isUpperCase(name.charAt(a.length())))) {
          out.add(b + name.substring(a.length()));
        }
        int at = name.indexOf(cap);
        if (at > 0 && (at + cap.length() == name.length() || Character.isUpperCase(name.charAt(at + cap.length())))) {
          out.add(name.substring(0, at) + capB + name.substring(at + cap.length()));
        }
      }
    }
    return out;
  }

  /** Locals and parameters of the method around {@code t}, by name. */
  private static Map<String, Symbol> declared(Tree t) {
    Tree m = t;
    while (m != null && !m.is(Tree.Kind.METHOD, Tree.Kind.CONSTRUCTOR)) {
      m = m.parent();
    }
    Map<String, Symbol> out = new java.util.HashMap<>();
    if (m != null) {
      Trees.collect(m, VariableTree.class).forEach(v -> out.put(v.simpleName().name(), v.symbol()));
      ((org.sonar.plugins.java.api.tree.MethodTree) m).parameters().forEach(v -> out.put(v.simpleName().name(), v.symbol()));
    }
    return out;
  }

  /** {@code range(start, start)} where {@code end} is declared and never used: the second was meant to be its twin. */
  private void argumentTwin(MethodInvocationTree call) {
    List<IdentifierTree> ids = new ArrayList<>();
    for (ExpressionTree a : call.arguments()) {
      if (Trees.skip(a) instanceof IdentifierTree id && Trees.isLocal(id.symbol())) {
        ids.add(id);
      }
    }
    for (int j = 1; j < ids.size(); j++) {
      for (int i = 0; i < j; i++) {
        if (!ids.get(i).symbol().equals(ids.get(j).symbol())) {
          continue;
        }
        Map<String, Symbol> vars = declared(call);
        for (String twin : twins(ids.get(j).name())) {
          Symbol t = vars.get(twin);
          if (t != null && t.usages().isEmpty()) {
            reportIssue(ids.get(j), "\"" + ids.get(j).name() + "\" is passed twice while \"" + twin + "\" is never used: did you mean \""
              + twin + "\"?");
            return;
          }
        }
      }
    }
  }

  /** {@code minWidth = 0; minWidth = 100;} next to an unassigned {@code maxWidth}: the second was meant for the twin. */
  private void constantToTwin(BlockTree block) {
    List<StatementTree> body = block.body();
    for (int k = 1; k < body.size(); k++) {
      org.sonar.plugins.java.api.tree.AssignmentExpressionTree a = constantAssignment(body.get(k - 1));
      org.sonar.plugins.java.api.tree.AssignmentExpressionTree b = constantAssignment(body.get(k));
      Symbol va = a == null ? null : Trees.symbol(a.variable());
      if (b == null || va == null || !va.equals(Trees.symbol(b.variable()))) {
        continue;
      }
      Symbol owner = va.owner();
      for (String twin : twins(va.name())) {
        Symbol t = Trees.isLocal(va) ? declared(block).get(twin) : owner != null && owner.isTypeSymbol()
          ? ((Symbol.TypeSymbol) owner).lookupSymbols(twin).stream().findFirst().orElse(null) : null;
        if (t != null && !Trees.assigns(block, t)) {
          reportIssue(b.variable(), "\"" + va.name() + "\" is set twice in a row while \"" + twin + "\" is not set: did you mean \""
            + twin + "\"?");
          break;
        }
      }
    }
  }

  private static org.sonar.plugins.java.api.tree.AssignmentExpressionTree constantAssignment(StatementTree st) {
    return st instanceof ExpressionStatementTree es && es.expression() instanceof org.sonar.plugins.java.api.tree.AssignmentExpressionTree a
      && a.is(Tree.Kind.ASSIGNMENT) && a.expression().asConstant().isPresent() ? a : null;
  }

  /** Longer statements are data (an embedded document, a generated table), never a renamed copy. */

  /** Literals, names, numbers and single characters; a plain scan, no regex (an 8,000-character literal is fine). */
  static List<String> tokens(String text) {
    List<String> out = new ArrayList<>();
    int i = 0;
    int n = text.length();
    while (i < n) {
      char c = text.charAt(i);
      int start = i;
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (c == '"' || c == '\'') {
        i++;
        while (i < n && text.charAt(i) != c) {
          i += text.charAt(i) == '\\' ? 2 : 1;
        }
        i = Math.min(n, i + 1);
      } else if (Character.isJavaIdentifierStart(c)) {
        while (i < n && Character.isJavaIdentifierPart(text.charAt(i))) {
          i++;
        }
      } else if (Character.isDigit(c)) {
        while (i < n && Character.isLetterOrDigit(text.charAt(i))) {
          i++;
        }
      } else {
        i++;
      }
      out.add(text.substring(start, i));
    }
    return out;
  }

  @Override
  public void visitNode(Tree tree) {
    if (tree instanceof BlockTree block && summaries.get().options().referenceParity()) {
      guarded(block, () -> constantToTwin(block));
    }
    if (tree instanceof MethodInvocationTree call && summaries.get().options().referenceParity()) {
      guarded(call, () -> argumentTwin(call));
    }
    List<Copy> copies = new ArrayList<>();
    if (tree instanceof BlockTree block) {
      for (StatementTree st : block.body()) {
        if (st instanceof VariableTree v && v.initializer() != null) {
          copies.add(getter(Trees.skip(v.initializer()), v.simpleName().name(), null));
        } else {
          copies.add(st instanceof ExpressionStatementTree es && Trees.skip(es.expression()) instanceof MethodInvocationTree m ? copy(m) : null);
        }
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
      int at = i;
      if (ref != null && c != null && ref.prop().equals(ref.srcProp()) && ref.source().equals(c.source())
        && c.srcProp().equals(ref.srcProp()) && !c.prop().equals(c.srcProp()) && !repeated(copies, i, (j, x) -> x.prop().equals(c.prop())
          && x.srcProp().equals(c.srcProp()))) {
        reportIssue(c.value(), "Value \"" + ref.srcProp() + "\" is copied into \"" + c.prop()
          + "\" as in the line above; it might be \"" + c.prop() + "\".");
      }
      Copy next = i + 1 < copies.size() ? copies.get(i + 1) : null;
      if (ref != null && c != null && next != null && c.target() != null && c.target().equals(ref.target()) && c.target().equals(next.target())
        && ref.source().equals(next.source()) && !c.source().equals(ref.source()) && c.prop().equals(c.srcProp())
        && ref.prop().equals(ref.srcProp()) && next.prop().equals(next.srcProp())
        && !repeated(copies, i, (j, x) -> Math.abs(j - at) > 1 && x.source().equals(c.source()) && c.target().equals(x.target()))) {
        reportIssue(c.value(), "\"" + c.prop() + "\" is read from \"" + c.source().name() + "\" while the lines around it read from \""
          + ref.source().name() + "\"; is it copied from the wrong object?");
      }
    }
    pairs(tree);
  }

  /** Another copy of the block (not number {@code i}) plays the same roles: the mapping is deliberate. */
  private static boolean repeated(List<Copy> copies, int i, java.util.function.BiPredicate<Integer, Copy> sameRoles) {
    for (int j = 0; j < copies.size(); j++) {
      if (j != i && copies.get(j) != null && sameRoles.test(j, copies.get(j))) {
        return true;
      }
    }
    return false;
  }

  /** {@code x.setFirst(a.getFirst())} or {@code builder.first(a.getFirst())} as (first, a, first). */
  private static Copy copy(MethodInvocationTree m) {
    if (m.arguments().size() != 1) {
      return null;
    }
    ExpressionTree r = Trees.receiver(m);
    Symbol target = r == null ? null : Trees.symbol(r);
    return getter(Trees.skip(m.arguments().get(0)), Trees.name(m).replaceFirst("^(set|with)(?=[A-Z])", ""), target);
  }

  /** {@code source.getX()} (or {@code Helper.getX()}) as a copy into {@code prop}, or null. */
  private static Copy getter(ExpressionTree value, String prop, Symbol target) {
    if (!(value instanceof MethodInvocationTree g) || !g.arguments().isEmpty()) {
      return null;
    }
    ExpressionTree src = Trees.receiver(g);
    Symbol s = src == null ? null : src instanceof IdentifierTree id && !id.symbol().isUnknown() ? id.symbol() : Trees.symbol(src);
    String getter = Trees.name(g);
    if (s == null || !getter.matches("(get|is)[A-Z].*")) {
      return null;
    }
    return new Copy(g, decap(prop), s, decap(getter.replaceFirst("^(get|is)", "")), target);
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
