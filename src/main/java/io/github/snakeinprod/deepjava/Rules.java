package io.github.snakeinprod.deepjava;

import io.github.snakeinprod.deepjava.checks.*;
import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.sonar.api.rules.RuleType;
import org.sonar.plugins.java.api.JavaCheck;

/** All rules with their metadata. The HTML description is built from {@link Spec#description} and {@link Spec#example}. */
public final class Rules {

  private Rules() {
  }

  /**
   * @param test the rule analyses test code (registered as a test check)
   */
  public record Spec(String key, String name, RuleType type, String severity, int[] cwe, String[] tags, boolean test,
    String description, String example, Function<Supplier<Summaries>, JavaCheck> check) {

    public String html() {
      StringBuilder sb = new StringBuilder("<p>").append(description).append("</p>\n");
      if (example != null) {
        sb.append("<h2>Noncompliant code example</h2>\n<pre>\n").append(example.replace("&", "&amp;").replace("<", "&lt;")).append("\n</pre>\n");
      }
      if (cwe.length > 0) {
        sb.append("<h2>Resources</h2>\n<ul>\n");
        for (int c : cwe) {
          sb.append("  <li><a href=\"https://cwe.mitre.org/data/definitions/").append(c).append("\">CWE-").append(c).append("</a></li>\n");
        }
        sb.append("</ul>\n");
      }
      return sb.toString();
    }
  }

  private static Spec bug(String key, String name, int[] cwe, String desc, String example, Function<Supplier<Summaries>, JavaCheck> check) {
    return new Spec(key, name, RuleType.BUG, "MAJOR", cwe, tags(cwe, "deep"), false, desc, example, check);
  }

  private static Spec vuln(String key, String name, int[] cwe, String desc, String example) {
    return new Spec(key, name, RuleType.VULNERABILITY, "CRITICAL", cwe, tags(cwe, "taint"), false, desc, example,
      s -> TaintChecks.create(key, s));
  }

  private static Spec test(String key, String name, String desc, String example, Function<Supplier<Summaries>, JavaCheck> check) {
    return new Spec(key, name, RuleType.CODE_SMELL, "MAJOR", new int[0], new String[] {"tests", "deep"}, true, desc, example, check);
  }

  private static String[] tags(int[] cwe, String tag) {
    return cwe.length > 0 ? new String[] {"cwe", tag} : new String[] {tag};
  }

  private static int[] cwe(int... c) {
    return c;
  }

  private static final String TAINT = " The data flow is followed across methods and files.";

  /**
   * Rules left out of the default profile: on real projects most of their issues were intended code (see the
   * benchmark in the README). They stay available for teams that want them.
   */
  public static final java.util.Set<String> NOT_IN_DEFAULT_PROFILE = java.util.Set.of(
    FindingChecks.IgnoredResult.KEY, FindingChecks.MissingSuperCall.KEY);

  public static final List<Spec> ALL = List.of(
    // 1. nullness
    bug(NullReturnDereferenceCheck.KEY, "Values that can be null should not be dereferenced across methods", cwe(476, 690),
      "A method that can return <code>null</code> on some path (directly, through other methods, or a library method such as "
        + "<code>Map.get</code>) is called and its result is dereferenced without a check. A null coming from project code is "
        + "reported only when most other call sites check the result.",
      "String host(Config c) { return c.props().get(\"host\"); }\nint port(Config c) { return host(c).indexOf(':'); } // Noncompliant",
      NullReturnDereferenceCheck::new),
    bug(NullArgumentCheck.KEY, "Null should not be passed to a parameter that is always dereferenced", cwe(476),
      "<code>null</code> (or a value that can be null) is passed to a method that dereferences that parameter on every path.",
      "void send(Message m) { queue.add(m.id()); }\nsend(null); // Noncompliant", NullArgumentCheck::new),
    bug(DereferenceBeforeNullCheckCheck.KEY, "Values should not be compared to null after they were dereferenced", cwe(476),
      "A variable is dereferenced and compared to <code>null</code> later: either the check is dead or the dereference is the bug.",
      "int n = s.length();\nif (s == null) { return 0; } // Noncompliant", DereferenceBeforeNullCheckCheck::new),
    bug(NullCheckedAtMostCallSitesCheck.KEY, "Results null-checked at most call sites should be checked here", cwe(476, 252),
      "Callers of the method check its result for null at most call sites (default 80% of at least 5; 90% for plain getters), "
        + "and this one dereferences it without a check.",
      "String s = repo.find(id);   // checked for null at 7 of 8 call sites\nreturn s.length(); // Noncompliant",
      NullCheckedAtMostCallSitesCheck::new),
    bug(NullAssignedThenDereferencedCheck.KEY, "Variables set to null on one path should not be dereferenced after the paths join", cwe(476),
      "A local variable is set to <code>null</code> in an <code>if</code>, <code>catch</code> or <code>switch</code> branch that does "
        + "not leave the method, and is dereferenced after the branch without a check.",
      "if (f == null || !f.isActive()) { f = null; }\nreturn f.getName(); // Noncompliant", NullAssignedThenDereferencedCheck::new),
    // 2. resources
    bug(ResourceFromHelperCheck.KEY, "Resources returned by helper methods should be closed", cwe(404, 772),
      "A method returns a new resource (stream, connection, statement...) and the caller neither closes it, returns it, stores "
        + "it nor passes it on.",
      "InputStream open(Path p) throws IOException { return Files.newInputStream(p); }\nvoid read(Path p) throws IOException {\n"
        + "  InputStream in = open(p); // Noncompliant\n  in.read();\n}", ResourceFromHelperCheck::new),
    bug(ResourceClosedTwiceCheck.KEY, "Resources should not be closed twice", cwe(675),
      "A resource is closed, also by a helper method that closes its parameter, and closed again.",
      "closeQuietly(in);\nin.close(); // Noncompliant", ResourceClosedTwiceCheck::new),
    bug(ResourceUsedAfterCloseCheck.KEY, "Resources should not be used after they are closed", cwe(672),
      "A stream, connection or channel is used after it was closed, also when a helper method closed it.",
      "closeQuietly(conn);\nconn.createStatement(); // Noncompliant", ResourceUsedAfterCloseCheck::new),
    // 3. taint
    vuln("TaintedSql", "SQL queries should not be built from user input", cwe(89),
      "User-controlled data reaches a SQL, JPQL or HQL query string. Use bind parameters (<code>PreparedStatement</code>)." + TAINT,
      "String id = request.getParameter(\"id\");\nstatement.executeQuery(\"SELECT * FROM t WHERE id = \" + id); // Noncompliant"),
    vuln("TaintedCommand", "OS commands should not be built from user input", cwe(78),
      "User-controlled data reaches <code>Runtime.exec</code> or <code>ProcessBuilder</code>." + TAINT,
      "Runtime.getRuntime().exec(\"ping \" + request.getParameter(\"host\")); // Noncompliant"),
    vuln("TaintedPath", "File paths should not be built from user input", cwe(22),
      "User-controlled data reaches a file path. Normalise it and check it stays under a base directory." + TAINT,
      "new File(base, request.getParameter(\"name\")); // Noncompliant"),
    vuln("TaintedUrl", "Server-side requests should not use user-controlled URLs", cwe(918),
      "User-controlled data selects the target of an outgoing HTTP or socket connection (SSRF). Check the host against an allow-list." + TAINT,
      "new URL(request.getParameter(\"url\")).openStream(); // Noncompliant"),
    vuln("TaintedLdap", "LDAP filters should not be built from user input", cwe(90),
      "User-controlled data reaches an LDAP search filter." + TAINT,
      "ctx.search(\"ou=people\", \"(uid=\" + user + \")\", controls); // Noncompliant"),
    vuln("TaintedXPath", "XPath expressions should not be built from user input", cwe(643),
      "User-controlled data reaches an XPath expression." + TAINT,
      "xpath.evaluate(\"//user[name='\" + name + \"']\", doc); // Noncompliant"),
    vuln("TaintedLog", "Logs should not contain unneutralised user input", cwe(117),
      "User-controlled data is logged without removing line breaks, so an attacker can forge log entries." + TAINT,
      "log.info(\"login \" + request.getParameter(\"user\")); // Noncompliant"),
    vuln("TaintedHeader", "HTTP headers should not contain user input", cwe(113),
      "User-controlled data is written into an HTTP response header (response splitting)." + TAINT,
      "response.setHeader(\"X-User\", request.getParameter(\"user\")); // Noncompliant"),
    vuln("TaintedRedirect", "Redirects should not use user-controlled targets", cwe(601),
      "User-controlled data selects a redirect target (open redirect)." + TAINT,
      "response.sendRedirect(request.getParameter(\"next\")); // Noncompliant"),
    vuln("TaintedExpression", "Expression languages should not evaluate user input", cwe(917),
      "User-controlled data is parsed as a SpEL, OGNL or EL expression." + TAINT,
      "parser.parseExpression(request.getParameter(\"expr\")); // Noncompliant"),
    vuln("TaintedFormatString", "Format strings should not come from user input", cwe(134),
      "User-controlled data is used as a format string." + TAINT,
      "String.format(request.getParameter(\"fmt\"), value); // Noncompliant"),
    vuln("TaintedSize", "Sizes, indexes and loop bounds should not come from unchecked user input", cwe(129, 789, 400, 606),
      "A number parsed from user input is used as an array index, an allocation size or a loop bound without a range check." + TAINT,
      "int n = Integer.parseInt(request.getParameter(\"n\"));\nbyte[] buf = new byte[n]; // Noncompliant"),
    vuln("TaintedSecurityDecision", "Security decisions should not rely on cookies or unverified tokens", cwe(565, 345),
      "A cookie value or the claims of a JWT that was decoded without verification decide a security check." + TAINT,
      "DecodedJWT jwt = JWT.decode(token);\nif (jwt.getClaim(\"role\").asString().equals(\"admin\")) { ... } // Noncompliant"),
    // 4. ranges
    bug(NegativeIndexCheck.KEY, "Results of search methods should be checked before they are used as an index", cwe(129, 125),
      "<code>indexOf</code>, <code>lastIndexOf</code>, <code>binarySearch</code> or a project method returns -1 when nothing is "
        + "found, and the result is used as an index or size without a check.",
      "s.substring(s.indexOf(':')); // Noncompliant", NegativeIndexCheck::new),
    bug(OffByOneLoopCheck.KEY, "Loop bounds should not allow an index equal to the length", cwe(119, 125, 193),
      "A loop runs while <code>i &lt;= length</code> and indexes with <code>i</code>, so the last iteration is out of bounds.",
      "for (int i = 0; i <= a.length; i++) { sum += a[i]; } // Noncompliant", OffByOneLoopCheck::new),
    bug(ArrayCopyBoundsCheck.KEY, "Array copies should fit the arrays", cwe(119),
      "<code>System.arraycopy</code> or <code>Arrays.copyOfRange</code> is called with constant bounds that do not fit.",
      "int[] dst = new int[4];\nSystem.arraycopy(src, 0, dst, 0, 8); // Noncompliant", ArrayCopyBoundsCheck::new),
    bug(LoopCounterOverflowCheck.KEY, "Loop counters should be able to reach the loop bound", cwe(190, 835),
      "A <code>byte</code>, <code>short</code> or <code>char</code> counter is compared with a bound it can never reach, or an "
        + "<code>int</code> counter with <code>Integer.MAX_VALUE</code>: the counter overflows and the loop never ends.",
      "for (byte b = 0; b < 200; b++) { ... } // Noncompliant", LoopCounterOverflowCheck::new),
    bug(IndexCheckedAfterUseCheck.KEY, "Indexes should be checked before they are used", cwe(129),
      "An index is used and only checked against the bounds afterwards.",
      "char c = s.charAt(i);\nif (i < s.length()) { ... } // Noncompliant", IndexCheckedAfterUseCheck::new),
    bug(NarrowingCastCheck.KEY, "Time stamps and file sizes should not be narrowed to int", cwe(197, 681),
      "A <code>long</code> that is routinely larger than <code>Integer.MAX_VALUE</code> (a time stamp, a file size) is cast to a "
        + "narrower type and wraps around.",
      "int seed = (int) System.currentTimeMillis(); // Noncompliant", NarrowingCastCheck::new),
    bug(DivisionByPossibleZeroCheck.KEY, "Divisors that can be zero should be checked", cwe(369),
      "An integer is divided by a size or count that can be 0 (an empty collection, a zero count) with no check in the method.",
      "int avg = total / items.size(); // Noncompliant", DivisionByPossibleZeroCheck::new),
    // 5. statistics
    bug(FindingChecks.IgnoredResult.KEY, "Return values checked at most call sites should be checked here", cwe(252),
      "The result of a method is used at most of its call sites in the project (default 80% of at least 5) but ignored here.",
      "file.delete(); // Noncompliant: the result is checked at 9 of 10 call sites", s -> new FindingChecks.IgnoredResult(s)),
    bug(FindingChecks.MissingSuperCall.KEY, "Overrides should call super when most overrides do", cwe(),
      "Most overrides of a method in the project call <code>super</code>, but this one does not.",
      "@Override protected void onStop() { cleanup(); } // Noncompliant: 6 of 7 overrides call super.onStop()",
      s -> new FindingChecks.MissingSuperCall(s)),
    bug(FindingChecks.InconsistentLocking.KEY, "Fields accessed under a lock at most places should always be", cwe(362, 366),
      "A field is read or written while holding a lock at most places in the project, but not here (data race).",
      "count++; // Noncompliant: count is accessed under a lock at 8 of 9 places", s -> new FindingChecks.InconsistentLocking(s)),
    // 6. concurrency
    bug(FindingChecks.GuardedBy.KEY, "Fields annotated @GuardedBy should only be accessed with the lock", cwe(362),
      "A field annotated <code>@GuardedBy</code> is accessed without holding that lock.",
      "@GuardedBy(\"this\") int count;\nvoid inc() { count++; } // Noncompliant", s -> new FindingChecks.GuardedBy(s)),
    bug(FindingChecks.MixedLocks.KEY, "The same field should be protected by the same lock", cwe(667),
      "A field is protected by one lock in some places and by a different lock in others, so the locks do not exclude each other.",
      "synchronized (a) { n++; }\nsynchronized (b) { n--; } // Noncompliant", s -> new FindingChecks.MixedLocks(s)),
    bug(CheckThenActCheck.KEY, "Conditions should be checked inside the lock that protects the action", cwe(367),
      "A condition on shared state is read outside a lock and acted on inside it without checking again (check-then-act).",
      "if (!cache.containsKey(k)) { // Noncompliant\n  synchronized (this) { cache.put(k, load(k)); }\n}", CheckThenActCheck::new),
    bug(FindingChecks.LockNotReleased.KEY, "Locks acquired in helper methods should be released", cwe(667),
      "A method calls a helper that returns with a lock held, and never releases it.",
      "void acquire() { lock.lock(); }\nvoid work() { acquire(); doWork(); } // Noncompliant", s -> new FindingChecks.LockNotReleased(s)),
    bug(FindingChecks.NonReentrantReacquired.KEY, "Non-reentrant locks should not be acquired twice", cwe(764),
      "A <code>StampedLock</code> is acquired again (also inside a called method) while already held: the thread blocks itself.",
      "long s = lock.writeLock();\nupdate(); // Noncompliant: update() calls lock.writeLock()", s -> new FindingChecks.NonReentrantReacquired(s)),
    bug(FindingChecks.StaticFieldInstanceLock.KEY, "Static fields should not be guarded by an instance lock", cwe(667),
      "A static field is written while holding only an instance lock; another instance does not exclude it.",
      "synchronized void reset() { total = 0; } // Noncompliant: total is static", s -> new FindingChecks.StaticFieldInstanceLock(s)),
    bug(FindingChecks.LockOrder.KEY, "Locks should be acquired in a consistent order", cwe(833),
      "Two locks are acquired in opposite orders on different paths, also through method calls, which can deadlock.",
      "synchronized (a) { synchronized (b) { } }\nsynchronized (b) { synchronized (a) { } } // Noncompliant", s -> new FindingChecks.LockOrder(s)),
    // 7. exceptions
    bug(FindingChecks.UncheckedInEntryPoint.KEY, "Unchecked exceptions should not escape thread and task entry points", cwe(248),
      "A library call that throws on bad input (e.g. <code>Integer.parseInt</code>) can end a <code>Runnable.run</code>, "
        + "<code>Callable.call</code>, scheduled method, message listener or <code>main</code>: the thread dies or the message is lost.",
      "public void run() { int n = Integer.parseInt(read()); } // Noncompliant", s -> new FindingChecks.UncheckedInEntryPoint(s)),
    bug(FindingChecks.UnreachableCatch.KEY, "Catch blocks should be reachable", cwe(561),
      "A project exception is caught, but nothing in the <code>try</code> block can throw it.",
      "try { compute(); } catch (ValidationException e) { ... } // Noncompliant: compute() never throws it",
      s -> new FindingChecks.UnreachableCatch(s)),
    bug(FindingChecks.CodeAfterThrow.KEY, "Code should not follow a call that always throws", cwe(561),
      "A method that always throws is called and more code follows; that code never runs.",
      "fail(\"bad state\");\ncleanup(); // Noncompliant", s -> new FindingChecks.CodeAfterThrow(s)),
    // 8. dead code
    bug(ConstantConditionCheck.KEY, "Conditions should not be constant because of what a method or field always holds", cwe(570, 571),
      "A condition is always true or false because the method it calls always returns the same constant, or because a "
        + "non-final field is only ever set to one value.",
      "static boolean enabled() { return false; }\nif (enabled()) { ... } // Noncompliant", ConstantConditionCheck::new),
    bug(FindingChecks.DeadEnumCase.KEY, "Switch cases should be reachable", cwe(561),
      "A <code>case</code> handles an enum constant that the project never uses anywhere else, so it never matches.",
      "case LEGACY: // Noncompliant: LEGACY is never used\n  migrate();", s -> new FindingChecks.DeadEnumCase(s)),
    bug(FindingChecks.EmptyMapLookup.KEY, "Lookups in maps that are never filled are always empty", cwe(570),
      "A map field is created empty and never filled, so <code>containsKey</code> is always false and <code>get</code> always null.",
      "private final Map<String, User> users = new HashMap<>();\nif (users.containsKey(id)) { ... } // Noncompliant",
      s -> new FindingChecks.EmptyMapLookup(s)),
    bug(FindingChecks.LombokUnreachable.KEY, "Lombok-generated members should not test @NonNull values for null", cwe(561),
      "Only with <code>sonar.deepjava.referenceParity=true</code>, as whole-program analyzers report it: a member that Lombok "
        + "generates for <code>@Data</code>, <code>@Value</code>, <code>@Builder</code> or <code>@NonNull</code> compares a value "
        + "declared <code>@NonNull</code> with null, so that branch is unreachable. Reported on the annotation line.",
      "@Data // Noncompliant: the generated equals() tests the @NonNull \"name\" for null\nclass User {\n  @NonNull private String name;\n}",
      s -> new FindingChecks.LombokUnreachable(s)),
    // 9. smaller rules
    bug(TernaryPrecedenceCheck.KEY, "Ternary operators should be parenthesized when mixed with arithmetic", cwe(783),
      "In <code>x == y ? 1 : 0 + z</code> the <code>+ z</code> belongs to the false branch only, which is rarely intended.",
      "int n = a == b ? 1 : 0 + offset; // Noncompliant", TernaryPrecedenceCheck::new),
    bug(ModifiedInForEachCheck.KEY, "Collections should not be modified while they are iterated", cwe(),
      "A collection is modified inside a for-each loop over it, which throws <code>ConcurrentModificationException</code>.",
      "for (String s : names) { if (s.isEmpty()) names.remove(s); } // Noncompliant", ModifiedInForEachCheck::new),
    bug(RegexFlagsCheck.KEY, "Regex flags should have an effect", cwe(),
      "A <code>Pattern</code> flag does nothing for that regex (<code>MULTILINE</code> without anchors, <code>UNICODE_CASE</code> "
        + "without <code>CASE_INSENSITIVE</code>...) or conflicts with another flag (<code>LITERAL</code>).",
      "Pattern.compile(\"a.b\", Pattern.MULTILINE); // Noncompliant", RegexFlagsCheck::new),
    bug(RegexPitfallCheck.KEY, "Regex literals should say what they mean", cwe(185),
      "A regex contains a class of one element (<code>[\\d]</code>), alternatives of single characters (<code>(^|-)</code>), or, in "
        + "<code>replaceFirst</code>/<code>replaceAll</code>/<code>split</code>, a \".\" that reads as a literal dot but matches any "
        + "character.",
      "name.replaceFirst(\".txt\", \"\"); // Noncompliant: also removes \"atxt\"", RegexPitfallCheck::new),
    bug(UnrelatedKeyTypeCheck.KEY, "Lookups should use a key of the collection's key type", cwe(),
      "A map or collection is searched with a value of an unrelated type (a <code>Integer</code> in a <code>Map&lt;Long, V&gt;</code>), "
        + "directly or through a project method that looks its parameter up in a field map: the lookup never matches.",
      "Map<Long, User> users = ...;\nusers.get(id); // Noncompliant: id is an int", UnrelatedKeyTypeCheck::new),
    test(AssertionAfterThrowCheck.KEY, "Assertions should not follow a call that always throws",
      "An assertion follows a call that always throws, so it never runs.",
      "service.failFast();\nassertEquals(1, count); // Noncompliant", AssertionAfterThrowCheck::new),
    test(SetupAfterAssertionCheck.KEY, "Tests should not set up state after the last assertion",
      "Statements after the last assertion of a test change state that is never checked.",
      "assertEquals(2, list.size());\nlist.add(\"x\"); // Noncompliant", SetupAfterAssertionCheck::new),
    test(ConflictingJUnitCheck.KEY, "JUnit annotations should not conflict",
      "A method combines test annotations that do not go together (<code>@Test</code> with <code>@ParameterizedTest</code>, a test "
        + "with a lifecycle annotation), or a class mixes JUnit 4 and JUnit 5 tests.",
      "@Test @ParameterizedTest void check(int n) { } // Noncompliant", ConflictingJUnitCheck::new),
    vuln("SecretLeak", "Secrets should not be written to logs, exception messages or standard output", cwe(532),
      "A password, key or token (by name: field, parameter or getter) reaches a log call, an exception message or "
        + "<code>System.out</code>." + TAINT,
      "log.debug(\"connecting with \" + config.getPassword()); // Noncompliant"),
    new Spec(UnboundedAuthRetryCheck.KEY, "Authentication should not be retried without a limit", RuleType.VULNERABILITY, "MAJOR",
      cwe(307), tags(cwe(307), "deep"), false, "Authentication is retried in a loop that has no attempt limit.",
      "while (!authenticated) { authenticated = login(user, read()); } // Noncompliant", UnboundedAuthRetryCheck::new),
    bug(SwappedArgumentsCheck.KEY, "Arguments should not be passed in swapped order", cwe(683),
      "Two arguments are named exactly like the parameters of the called method, but in the opposite order.",
      "void move(int from, int to) { ... }\nmove(to, from); // Noncompliant", SwappedArgumentsCheck::new),
    new Spec(TimingUnsafeSecretComparisonCheck.KEY, "Secrets should be compared in constant time", RuleType.VULNERABILITY, "MAJOR",
      cwe(208), tags(cwe(208), "deep"), false, "A password, token, signature or similar secret is compared with <code>String.equals</code> "
        + "or <code>Arrays.equals</code>, which return at the first difference: the time taken reveals how much matched. Use "
        + "<code>MessageDigest.isEqual</code>.",
      "if (token.equals(expectedToken)) { ... } // Noncompliant", TimingUnsafeSecretComparisonCheck::new),
    bug(CopyPasteCheck.KEY, "Expressions copied from a neighbour should rename every identifier", cwe(),
      "Next to <code>a.x - b.x</code>, the expression <code>a.y - b.x</code> probably meant <code>b.y</code>.",
      "double dx = a.x - b.x;\ndouble dy = a.y - b.x; // Noncompliant", CopyPasteCheck::new));
}
