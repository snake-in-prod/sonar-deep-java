# Spec: open-source SonarQube plugin for deep Java analysis

## Goal
SonarQube Community Build analyses Java one method at a time (symbolic execution in
`sonar-java`, plus pattern rules). Commercial static analyzers and the paid SonarQube
editions also find defects that need whole-program analysis: values that cross method,
file and module boundaries, taint flows, array bounds and project-wide statistics.
This plugin adds those analyses to the Community Build as ordinary Sonar rules.

Target: SonarQube Community Build 25.x/26.x, `sonar-plugin-api` current, `sonar-java`
`java-frontend` for syntax trees, Java 17-25 sources, Gradle and Maven projects.

## Non-goals
- Re-implementing rules that already exist in `sonar-java` or the `sonar-findbugs`
  plugin (SpotBugs, fb-contrib, Find Security Bugs). Section 10 lists them; the plugin
  only goes beyond them.
- Android, Scala, JSP, C/C++.

## Architecture
1. **Pre-pass sensor** reads all bytecode (`sonar.java.binaries`, `sonar.java.libraries`)
   and sources, builds a call graph (CHA, plus RTA where cheap) and per-method summaries.
   Summaries are cached by class file hash, so a re-analysis only recomputes changed classes
   and their callers.
2. **Summary contents** per method: may return null; return value ranges; parameters it
   dereferences; parameters it closes, stores or returns (ownership of `AutoCloseable`);
   exceptions it may throw; locks it takes or releases; taint propagation (parameter to
   return, parameter to field, source to sink).
3. **Rules** run per file in the normal `JavaFileScanner` pass and read the summaries.
   Each issue has secondary locations along the path (call site, callee line, origin of
   the value).
4. **Library models**: JSON files in the plugin for JDK, Spring, Jakarta, Jackson, Kafka,
   RabbitMQ, JDBC, Apache Commons, Guava (null returns, resources, sources, sinks,
   sanitizers, methods whose result must be checked). Users can add their own through
   a setting.
5. **Budgets**: limits on path length, call depth and time per method, so a large
   monorepo (around 1-2 M LOC) finishes within the time of a normal build.

Precision comes first: each rule targets less than 20% false positives on real projects.
Statistical rules report only when the majority is strong (default at least 80% of at
least 5 call sites).

## 1. Interprocedural nullness (highest value)
Defects (CWE-476, CWE-690):
- A method returns `null` on some path, and a caller in another file dereferences the
  result without a check.
- A JDK or library method can return `null` (`Map.get`, `System.getProperty`,
  `System.getenv`, `File.listFiles`, `Class.getResource`, `ResultSet.getString`,
  `HttpServletRequest.getHeader`, ...) and the result is dereferenced.
- A value is compared to `null` and then dereferenced on a path where it is null,
  including on exception paths (`catch`, `finally`) and in later loop iterations.
- A value is dereferenced first and compared to `null` later. The check is dead, or the
  dereference is the bug.
- `null` is passed to a parameter that the callee always dereferences.
- `@Nullable` / `@NonNull` contracts (JSpecify, JSR-305, Jakarta, Spring, Lombok)
  broken across files.

Existing Sonar coverage: `java:S2259`, `java:S2637`, `java:S4449`, `java:S2447`,
`java:S3655`, all limited to one method or one file.
Example:
```java
String host(Config c) { return c.props().get("host"); }   // Map.get may return null
int port(Config c) { return host(c).indexOf(':'); }        // NPE, other method
```

## 2. Interprocedural resources and use after close
Defects (CWE-404, CWE-772, CWE-775, CWE-672):
- `AutoCloseable` opened and not closed on some path, also when it is opened in a helper
  method, kept in a local or a field, or lost on an exception edge.
- A resource closed twice (also by a callee).
- A stream, connection, channel or lock used after `close()` / `unlock()`.
- A closed resource passed to another method that uses it.
Ownership rules: a resource returned or stored in a field owned by the object is not a
leak. A resource passed to a constructor that wraps it (`new BufferedReader(r)`) moves
ownership.
Existing Sonar coverage: `java:S2095`, `java:S2093` (one method).

## 3. Taint analysis
Sources: servlet and Spring MVC/WebFlux request data (`@RequestParam`, `@PathVariable`,
`@RequestBody`, headers, cookies), sockets, files, environment variables, command line,
message listeners (`@KafkaListener`, `@RabbitListener`, JMS), deserialized objects.
Sinks and defects:
- array index, allocation size or loop bound from input without a bound check (CWE-129,
  CWE-789, CWE-400, CWE-606);
- format string (CWE-134);
- URL or socket target, SSRF (CWE-918);
- file path (CWE-22), SQL/JPQL/HQL (CWE-89), OS command (CWE-78), LDAP (CWE-90),
  XPath (CWE-643), logs, CRLF (CWE-117), HTTP headers (CWE-113), redirects (CWE-601),
  expression languages SpEL/OGNL/EL (CWE-917);
- a security decision based on a cookie or unverified JWT claims (CWE-565, CWE-345).
Sanitizers: integer range checks, allow-lists, `PreparedStatement` parameters,
`Path.normalize` plus `startsWith(base)`, `URI` host allow-lists, JWT verification calls.
Existing Sonar coverage: the taint rules (`java:S2076`, `java:S3649`, `java:S5131`,
`java:S5144`, `java:S2083`, `java:S5145`) exist only in commercial editions. Find Security
Bugs matches patterns without data flow.

## 4. Array, string and integer ranges
Value-range analysis (intervals, plus relations to `length` / `size()`) across methods.
Defects:
- index proven to be at or past `length` or negative on a path (CWE-119, CWE-125,
  CWE-129); also `String.charAt`, `substring`, `List.get`;
- off-by-one bounds checks (`i <= a.length`);
- index used before it is checked, or checked after it was passed to a method that
  indexes with it;
- a value known to be out of range still passed on;
- `-1` from `indexOf`, `read`, `binarySearch` used as an index or size without a check;
- `System.arraycopy`, `Arrays.copyOf(Range)` with sizes that do not fit;
- integer overflow in arithmetic, shifts and narrowing casts (CWE-190, CWE-191,
  CWE-197), including loop counters that overflow so the loop never ends.
Existing Sonar coverage: `java:S2184`, `java:S2676`, `java:S2692`, `java:S2189`,
`java:S2251` (local patterns). Array index rule `java:S6466` is commercial only.

## 5. Project-wide statistical rules
Project-wide majority vote, reported with the counts in the message
("checked at 14 of 15 call sites").
- Result of a method checked (null, boolean, status code) at most call sites but not
  here (CWE-252).
- Result of a method null-checked at most call sites but dereferenced here.
- `super.m()` called by most overrides of `m`, but not by this one.
- A field accessed under a lock at most places but not here (data race, CWE-362/366).

## 6. Concurrency
- Field declared or inferred as guarded by a lock (`@GuardedBy`, or access pattern)
  accessed without it.
- The same data protected by different locks in different places.
- Check-then-act: condition read outside the lock, acted on inside (CWE-367).
- A non-reentrant lock acquired twice on one path; a lock not released on some path
  across methods (CWE-667, CWE-764).
- A static field guarded by an instance lock (`synchronized` instance method or `this`).
- Lock-order cycles between threads (deadlock, CWE-833), including class
  initialisation deadlocks.
Existing Sonar coverage: `java:S2222` (one method), `java:S2445`, `java:S1860`,
`java:S2886`, `java:S2168`; SpotBugs `IS2_INCONSISTENT_SYNC` (local heuristic).

## 7. Exceptions
- Unchecked exception escaping a thread or task entry point (`Runnable.run`,
  `Callable.call`, `@Scheduled`, `@Async`, message listeners, `main`), so the thread dies
  or the message is lost (CWE-248). Includes library methods that document unchecked
  exceptions (`Integer.parseInt`, `UUID.fromString`, `Optional.get`, ...).
- `catch` block that can never run because the `try` body cannot throw that exception.
- Code after a call that always throws.

## 8. Dead code from project-wide values
- Conditions always true or false because of what a callee returns or of a value
  known in the whole program (constant-like fields, enum values that never reach a switch).
- `Map.containsKey` / `get` checks that are always true or false.
Existing Sonar coverage: `java:S2583`, `java:S2589` (one method).

## 9. Smaller rules
- Ternary precedence mistakes (`a + b ? c : d`, `x == y ? 1 : 0 + z`).
- Collection modified inside its own for-each loop (`ConcurrentModificationException`).
- Regex flags that conflict or do nothing.
- Tests: assertion after a call that always throws; setup after the last assertion;
  conflicting JUnit annotations.
- Secrets (passwords, keys, tokens) flowing into logs, exception messages or standard
  output (CWE-532).
- Authentication retried in a loop with no limit (CWE-307).
- Copy-paste with one identifier not renamed (`a.x - b.x`, `a.y - b.x`).

## 10. Already covered elsewhere: do not re-implement
Enable these instead (in a quality profile shipped with the plugin):
- `sonar-java`: `S1696`, `S1698`, `S1699`, `S1114`, `S2221` and the whole Sonar way.
- `sonar-findbugs`: the `findbugs` repository families NP_*, OS_*/ODR_*/OBL_* (leaks),
  RV_*, DMI_*, EC_*, SE_*, UWF_*, IS2_INCONSISTENT_SYNC, plus `fb-contrib` and
  `findsecbugs`.
The plugin's own profile = Sonar way + these + the plugin rules.

## Deliverables
- Maven or Gradle plugin project, Apache-2.0 or LGPL-3.0, GitHub CI building the jar.
- One rule class per defect type, rule metadata (HTML description, CWE tags, default
  severity, quality impact), a test file per rule (`// Noncompliant` markers, as in
  `sonar-java` checks).
- A benchmark: OWASP Benchmark (Java), Juliet Test Suite for Java (CWE subsets above) and
  2-3 open-source projects (e.g. Apache Kafka, Spring PetClinic, Keycloak); report true and
  false positives per rule in the README.
- Installation and configuration in the README; settings for budgets, library models,
  statistical thresholds.

## Order of work
1. Pre-pass, call graph, summary cache.
2. Section 1 (nullness) and 2 (resources).
3. Section 3 (taint).
4. Section 4 (ranges).
5. Sections 5-7.
6. Sections 8-9.
