package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;

/**
 * Regex literals that do not say what they mean: a class of one character ({@code [\d]}), alternatives of single
 * characters ({@code (^|-)}), or a "." meant literally in {@code replaceFirst(".txt", "")}.
 */
@Rule(key = RegexPitfallCheck.KEY)
public class RegexPitfallCheck extends DeepCheck {

  public static final String KEY = "RegexPitfall";

  private static final Set<String> STRING_REGEX = Set.of("matches", "replaceAll", "replaceFirst", "split");
  /** One element in a class: a shorthand ({@code \d}) or an ordinary character; {@code [.]} is a common way to escape. */
  private static final Pattern SINGLE_CLASS = Pattern.compile("(?<!\\\\)\\[(\\\\[dDwWsS]|[^\\]\\\\^.$*+?|(){}\\[-])\\]");
  /** {@code (a|b)}, {@code (^|-)}: every alternative is one character or anchor. */
  private static final Pattern SINGLE_ALTERNATIVES = Pattern.compile("(?<!\\\\)\\((\\?:)?+(\\\\.|[^|()\\\\])(\\|(?:\\\\.|[^|()\\\\]))++\\)");

  /** {@code \000}: an octal escape that reads like repeated zeros. */
  private static final Pattern OCTAL = Pattern.compile("(?<!\\\\)\\\\0[0-7]+");
  /** {@code " |,|\r"}: the whole regex is single characters as alternatives, without a group. */
  private static final Pattern TOP_ALTERNATIVES = Pattern.compile("(\\\\.|[^|\\\\()\\[\\]])(\\|(\\\\.|[^|\\\\()\\[\\]]))+");

  public RegexPitfallCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.METHOD_INVOCATION);
  }

  @Override
  public void visitNode(Tree tree) {
    guarded(tree, () -> check((MethodInvocationTree) tree));
  }

  private void check(MethodInvocationTree mit) {
    boolean onString = Trees.isCall(mit, "java.lang.String", STRING_REGEX.toArray(String[]::new));
    if (!onString && !Trees.isCall(mit, "java.util.regex.Pattern", "compile", "matches") || mit.arguments().isEmpty()) {
      return;
    }
    ExpressionTree arg = mit.arguments().get(0);
    // the constant value of a literal keeps its source escapes ("[\\d]" stays two backslashes)
    String regex = Trees.skip(arg) instanceof org.sonar.plugins.java.api.tree.LiteralTree lit && lit.is(Tree.Kind.STRING_LITERAL)
      ? lit.value().substring(1, lit.value().length() - 1).translateEscapes() : arg.asConstant(String.class).orElse(null);
    if (regex == null || regex.isEmpty()) {
      return;
    }
    String name = Trees.name(mit);
    Matcher single = SINGLE_CLASS.matcher(regex);
    Matcher alternatives = SINGLE_ALTERNATIVES.matcher(regex);
    if (single.find()) {
      reportIssue(arg, "\"" + single.group() + "\" is a class of one element; write \"" + single.group(1) + "\".");
    } else if (alternatives.find()) {
      reportIssue(arg, "\"" + alternatives.group() + "\" lists single characters as alternatives; use a character class "
        + "(or an anchor with a class, such as \"(?:^|[-])\").");
    } else if (summaries.get().options().referenceParity() && OCTAL.matcher(regex).find()) {
      reportIssue(arg, "\"" + OCTAL.matcher(regex).results().findFirst().orElseThrow().group() + "\" is an octal escape; write \"\\\\x00\" if a NUL "
        + "character is meant.");
    } else if (summaries.get().options().referenceParity() && TOP_ALTERNATIVES.matcher(regex).matches()) {
      reportIssue(arg, "The regex lists single characters as alternatives; use a character class.");
    } else if (onString && !"matches".equals(name) && (literalWithDot(regex) || ".".equals(regex) && summaries.get().options().referenceParity())) {
      reportIssue(arg, "\".\" in the regex of \"" + name + "()\" matches any character; escape it (\"\\\\.\") or use replace().");
    }
  }

  /** The regex reads like plain text with an unescaped "." and no other regex syntax: {@code ".txt"}, {@code "a.b"}. */
  private static boolean literalWithDot(String regex) {
    boolean dot = false;
    for (int i = 0; i < regex.length(); i++) {
      char c = regex.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '.') {
        dot = true;
      } else if ("[]()*+?{}|^$".indexOf(c) >= 0) {
        return false;
      }
    }
    return dot && regex.length() > 1;
  }
}
