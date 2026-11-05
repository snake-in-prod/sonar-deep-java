package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.tree.IfStatementTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.Tree;

/**
 * Reference parity mode only: two branches of an {@code if / else if} chain with the same body, whose conditions are
 * the same code except literals ({@code s.matches("[a-z]+")} and {@code s.matches("[0-9]+")}): one branch was copied
 * and not edited.
 */
@Rule(key = IdenticalBranchesCheck.KEY)
public class IdenticalBranchesCheck extends DeepCheck {

  public static final String KEY = "IdenticalBranches";

  public IdenticalBranchesCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.IF_STATEMENT);
  }

  @Override
  public void visitNode(Tree tree) {
    IfStatementTree first = (IfStatementTree) tree;
    if (!summaries.get().options().referenceParity() || first.parent() instanceof IfStatementTree p && p.elseStatement() == first) {
      return;
    }
    guarded(first, () -> {
      List<IfStatementTree> chain = new ArrayList<>();
      for (StatementTree s = first; s instanceof IfStatementTree i; s = i.elseStatement()) {
        chain.add(i);
      }
      for (int j = 1; j < chain.size(); j++) {
        for (int i = 0; i < j; i++) {
          if (same(chain.get(i), chain.get(j))) {
            reportIssue(chain.get(j).condition(), "This branch has the same body as the one at line " + Trees.line(chain.get(i).thenStatement())
              + ", and the conditions differ only in literals: is one of them a copy that was not fully edited?");
            break;
          }
        }
      }
    });
  }

  private boolean same(IfStatementTree a, IfStatementTree b) {
    String ba = text(a.thenStatement());
    String bb = text(b.thenStatement());
    if (ba.length() < 10 || ba.length() > MAX_TEXT || !ba.equals(bb)) {
      return false;
    }
    List<String> ca = CopyPasteCheck.tokens(text(a.condition()));
    List<String> cb = CopyPasteCheck.tokens(text(b.condition()));
    if (ca.size() != cb.size() || ca.equals(cb)) {
      return false;
    }
    for (int k = 0; k < ca.size(); k++) {
      if (!ca.get(k).equals(cb.get(k)) && !(isLiteral(ca.get(k)) && isLiteral(cb.get(k)))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isLiteral(String token) {
    char c = token.charAt(0);
    return c == '"' || c == '\'' || Character.isDigit(c);
  }

  /** Source text of a tree, comments dropped and whitespace collapsed. */
  private String text(Tree t) {
    var start = t.firstToken().range().start();
    var end = t.lastToken().range().end();
    List<String> lines = context.getFileLines();
    StringBuilder sb = new StringBuilder();
    for (int l = start.line(); l <= end.line() && sb.length() <= MAX_TEXT; l++) {
      String line = lines.get(l - 1);
      int from = l == start.line() ? start.columnOffset() : 0;
      int to = l == end.line() ? Math.min(end.columnOffset(), line.length()) : line.length();
      String part = line.substring(Math.min(from, to), to);
      int comment = part.indexOf("//");
      sb.append(comment >= 0 && part.indexOf('"') < 0 ? part.substring(0, comment) : part).append(' ');
    }
    return String.join(" ", sb.toString().trim().split("\\s+"));
  }

  private static final int MAX_TEXT = 2000;
}
