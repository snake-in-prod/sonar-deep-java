# Deep Java

Whole-program Java rules for SonarQube Community Build.

The Community Build analyses Java one method at a time. Deep Java first reads the compiled classes of the
whole project, builds per-method summaries, a class hierarchy and a call graph, and then reports defects that
cross method, file and module boundaries: interprocedural nullness, resources, taint (injection), ranges,
project-wide statistics, concurrency, exceptions and dead code. Issues are ordinary Sonar issues.

See [docs/SPEC.md](docs/SPEC.md) for the specification this plugin implements.

## Rules

50 rules. Rules marked * are not in the default profile (see the benchmark).

| Rule | What it finds | CWE |
|---|---|---|
| **1. nullness** | | |
| `NullReturnDereference` | Values that can be null should not be dereferenced across methods | 476, 690 |
| `NullPassedToDereferencedParameter` | Null should not be passed to a parameter that is always dereferenced | 476 |
| `DereferenceBeforeNullCheck` | Values should not be compared to null after they were dereferenced | 476 |
| **2. resources** | | |
| `ResourceFromHelperNotClosed` | Resources returned by helper methods should be closed | 404, 772 |
| `ResourceClosedTwice` | Resources should not be closed twice | 675 |
| `ResourceUsedAfterClose` | Resources should not be used after they are closed | 672 |
| **3. taint** | | |
| `TaintedSql` | SQL queries should not be built from user input | 89 |
| `TaintedCommand` | OS commands should not be built from user input | 78 |
| `TaintedPath` | File paths should not be built from user input | 22 |
| `TaintedUrl` | Server-side requests should not use user-controlled URLs | 918 |
| `TaintedLdap` | LDAP filters should not be built from user input | 90 |
| `TaintedXPath` | XPath expressions should not be built from user input | 643 |
| `TaintedLog` | Logs should not contain unneutralised user input | 117 |
| `TaintedHeader` | HTTP headers should not contain user input | 113 |
| `TaintedRedirect` | Redirects should not use user-controlled targets | 601 |
| `TaintedExpression` | Expression languages should not evaluate user input | 917 |
| `TaintedFormatString` | Format strings should not come from user input | 134 |
| `TaintedSize` | Sizes, indexes and loop bounds should not come from unchecked user input | 129, 789, 400, 606 |
| `TaintedSecurityDecision` | Security decisions should not rely on cookies or unverified tokens | 565, 345 |
| **4. ranges** | | |
| `NegativeIndexFromSearch` | Results of search methods should be checked before they are used as an index | 129, 125 |
| `OffByOneLoopBound` | Loop bounds should not allow an index equal to the length | 119, 125, 193 |
| `ArrayCopyOutOfBounds` | Array copies should fit the arrays | 119 |
| `LoopCounterOverflow` | Loop counters should be able to reach the loop bound | 190, 835 |
| `IndexCheckedAfterUse` | Indexes should be checked before they are used | 129 |
| `NarrowingCastOverflow` | Time stamps and file sizes should not be narrowed to int | 197, 681 |
| **5. statistics** | | |
| `IgnoredResultStatistical` | Return values checked at most call sites should be checked here | 252 |
| `MissingSuperCall` | Overrides should call super when most overrides do |  |
| `InconsistentLocking` | Fields accessed under a lock at most places should always be | 362, 366 |
| **6. concurrency** | | |
| `GuardedByViolation` | Fields annotated @GuardedBy should only be accessed with the lock | 362 |
| `MixedLocks` | The same field should be protected by the same lock | 667 |
| `CheckThenActOutsideLock` | Conditions should be checked inside the lock that protects the action | 367 |
| `LockNotReleasedAcrossMethods` | Locks acquired in helper methods should be released | 667 |
| `NonReentrantLockReacquired` | Non-reentrant locks should not be acquired twice | 764 |
| `StaticFieldInstanceLock` | Static fields should not be guarded by an instance lock | 667 |
| `LockOrderCycle` | Locks should be acquired in a consistent order | 833 |
| **7. exceptions** | | |
| `UncheckedExceptionInEntryPoint` | Unchecked exceptions should not escape thread and task entry points | 248 |
| `UnreachableCatch` | Catch blocks should be reachable | 561 |
| `CodeAfterAlwaysThrows` | Code should not follow a call that always throws | 561 |
| **8. dead code** | | |
| `ConstantConditionFromCallee` | Conditions should not be constant because of what a method or field always holds | 570, 571 |
| `DeadEnumCase` | Switch cases should be reachable | 561 |
| `EmptyMapLookup` | Lookups in maps that are never filled are always empty | 570 |
| **9. smaller rules** | | |
| `TernaryPrecedence` | Ternary operators should be parenthesized when mixed with arithmetic | 783 |
| `CollectionModifiedInForEach` | Collections should not be modified while they are iterated |  |
| `RegexFlagsUseless` | Regex flags should have an effect |  |
| `AssertionAfterAlwaysThrows` | Assertions should not follow a call that always throws |  |
| `SetupAfterLastAssertion` | Tests should not set up state after the last assertion |  |
| `ConflictingJUnitAnnotations` | JUnit annotations should not conflict |  |
| `SecretLeak` | Secrets should not be written to logs, exception messages or standard output | 532 |
| `UnboundedAuthRetry` | Authentication should not be retried without a limit | 307 |
| `CopyPasteIdentifier` | Expressions copied from a neighbour should rename every identifier |  |

\* `IgnoredResultStatistical`, `MissingSuperCall` and `UncheckedExceptionInEntryPoint` are available but not
activated by default.

All rules except those marked * are added to the built-in "Sonar way" Java profile.

### "Deep Java" profile

The profile of the spec is Sonar way + the [sonar-findbugs](https://github.com/spotbugs/sonar-findbugs)
families `NP_*`, `OS_*`, `ODR_*`, `OBL_*`, `RV_*`, `DMI_*`, `EC_*`, `SE_*`, `UWF_*`, `IS2_INCONSISTENT_SYNC`,
all of fb-contrib and Find Security Bugs + the Deep Java rules. [`docs/deep-java-profile.xml`](docs/deep-java-profile.xml)
holds everything beyond Sonar way: restore it (*Quality Profiles > Restore*) and set "Sonar way" as its parent,
so it follows Sonar way when the Java plugin is upgraded. Rules of plugins that are not installed are skipped.
Step-by-step commands: [docs/adr/0001-installation-and-profile.md](docs/adr/0001-installation-and-profile.md).

## Install

Tested on SonarQube Community Build 26.7 (Java plugin 8.34) and 26.10 (Java plugin 8.44). The scanner must
run on Java 21 or later (SonarQube provisions it by default).

1. Download `sonar-deep-java-<version>.jar` from [Releases](https://github.com/snake-in-prod/sonar-deep-java/releases).
2. Copy it to `extensions/plugins/` of your SonarQube server and restart.
3. Analyse with compiled classes available (`sonar.java.binaries`). Maven and Gradle scanners set this.
4. Optional: create the "Deep Java" profile (above).

Full procedure with verification and rollback, written to be followed by a person or an agent:
[docs/adr/0001-installation-and-profile.md](docs/adr/0001-installation-and-profile.md).

## Settings

| Property | Default | Meaning |
|----------|---------|---------|
| `sonar.deepjava.models` | none | Extra library model JSON files (see below) |
| `sonar.deepjava.cacheDir` | `~/.sonar/deepjava-cache` | Per-class summary cache; unchanged classes are not re-analysed |
| `sonar.deepjava.maxStepsPerMethod` | `20000` | Data-flow budget per method |
| `sonar.deepjava.maxVirtualTargets` | `8` | Virtual calls with more implementations are ignored |
| `sonar.deepjava.statistics.minCallSites` | `5` | Statistical rules need at least this many sites... |
| `sonar.deepjava.statistics.minRatio` | `0.8` | ...and this share of them agreeing |

### Library models

Built-in models: [`default.json`](src/main/resources/models/default.json) (nullable returns, lookup guards,
unchecked exceptions, resource factories) and [`taint.json`](src/main/resources/models/taint.json) (sources,
sinks, propagators, sanitizers). Files given in `sonar.deepjava.models` use the same keys and are merged:

```json
{
  "nullableReturns": ["com/acme/Registry#lookup"],
  "guards": {"com/acme/Registry#lookup": ["contains"]},
  "uncheckedThrows": {"com/acme/Ids#parse": "java/lang/IllegalArgumentException"},
  "resourceFactories": ["com/acme/Pool#borrow"],
  "sources": {"com/acme/Web#param": "USER"},
  "sinks": {"com/acme/Db#run": {"kind": "SQL", "args": [0]}},
  "propagators": {"com/acme/Text": "receiver"},
  "sanitizers": ["com/acme/Html#escape*"]
}
```

Methods are `owner#name` (all overloads) or `owner#name(descriptor)`, in JVM internal form; `*` matches any part.
Sink argument indexes exclude the receiver, which is `-1`. Taint source kinds: `USER`, `COOKIE`, `JWT`, `NET`,
`MSG`, `CMDLINE`, `ENV`, `FILE`, `DESER`, `SECRET`; sink kinds: `SQL`, `COMMAND`, `PATH`, `SSRF`, `LDAP`, `XPATH`,
`LOG`, `HEADER`, `REDIRECT`, `EXPRESSION`, `FORMAT`, `SIZE`, `DECISION`, `STDOUT`.

## How it works

1. **Pre-pass** (once per analysis, shared by all modules): every class in the `sonar.java.binaries` of each
   module, and in any `target/classes` or `build/classes/*/main` directory under the project, is read with ASM.
   Two data flows run per method: one for nullness, value use, locks and exceptions (branch-sensitive on null
   checks, with path conditions for "null only if an argument is null"), one for taint (labels for parameters,
   sources and call results; constant folding prunes dead branches; maps are tracked per constant key).
   Results are cached by class file hash.
2. **Solving**: call targets are resolved by class hierarchy analysis over project types; summaries are
   composed across calls (null returns, taint from parameters to returns and sinks, transitive locks and
   exceptions); library models are applied.
3. **Rules**: syntax-tree rules run in sonar-java's per-file pass and query the summaries; whole-program rules
   are computed once and reported on their files.

Precision comes first: a null that comes from project code is only reported when most call sites check it;
lookups whose content the author controls, overridden `return null` defaults, code after a call that always
throws, and similar patterns are not reported. The benchmark below shows what is left.

## Benchmark

### OWASP Benchmark 1.2 (taint rules)

| Category | TP | FN | FP | TN | True positive rate | False positive rate | Score |
|---|--:|--:|--:|--:|--:|--:|--:|
| Command injection (`TaintedCommand`) | 126 | 0 | 20 | 105 | 100% | 16% | +84 |
| LDAP injection (`TaintedLdap`) | 27 | 0 | 4 | 28 | 100% | 12% | +88 |
| Path traversal (`TaintedPath`) | 119 | 14 | 22 | 113 | 89% | 16% | +73 |
| SQL injection (`TaintedSql`) | 272 | 0 | 36 | 196 | 100% | 16% | +84 |
| XPath injection (`TaintedXPath`) | 15 | 0 | 2 | 18 | 100% | 10% | +90 |

Score is true positive rate minus false positive rate (Youden index), as in the OWASP scorecard.

### Juliet Test Suite for Java 1.3

Share of test cases with an issue in a `bad` method (true positives) and in a `good` method (false positives).

| CWE | Rule | Cases | Found | False positives |
|---|---|--:|--:|--:|
| 89 SQL injection | `TaintedSql` | 2220 | 73% | 3% |
| 78 OS command injection | `TaintedCommand` | 444 | 73% | 3% |
| 23 / 36 Path traversal | `TaintedPath` | 888 | 73% | 3% |
| 90 LDAP injection | `TaintedLdap` | 444 | 73% | 3% |
| 643 XPath injection | `TaintedXPath` | 444 | 73% | 3% |
| 113 HTTP response splitting | `TaintedHeader` | 1332 | 73% | 3% |
| 601 Open redirect | `TaintedRedirect` | 333 | 77% | 3% |
| 134 Format string | `TaintedFormatString` | 666 | 28% | 0% |
| 606 Unchecked loop condition | `TaintedSize` | 444 | 42% | 0% |
| 129 Array index | `TaintedSize` | 2664 | 27% | 7% |
| 400 / 789 Resource exhaustion, allocation | `TaintedSize` | 3031 | 24% | 0% |
| 476 NULL dereference | null rules | 198 | 11% | 2% |
| 690 NULL dereference from return | `NullReturnDereference` | 296 | 5% | 3% |

Injection rules miss the Juliet variants whose source is `System.getProperty`, a properties file or a database
read, which the default models leave out to keep real projects quiet. Format string and size rules only count
remote sources (requests, sockets, messages). Most Juliet null, resource, deadlock and unchecked-return cases
are within one method, where sonar-java's own rules (`S2259`, `S2095`, `S2222`, ...) apply; Deep Java targets
the cases that cross methods.

### Real projects

Mature libraries, analysed from their Maven Central class and source jars on SonarQube 26.7 with all rules.
Every issue was reviewed by hand.

| Project | Classes | Issues |
|---|--:|--:|
| commons-lang3 3.21.0 | 439 | 1 |
| commons-io 2.22.0 | 428 | 4 |
| jackson-databind 2.22.4 | 813 | 2 |
| kafka-clients 4.4.0 | 4345 | 15 |

By rule (default profile):

| Rule | Issues | True positive | False positive | Not confirmed |
|---|--:|--:|--:|--:|
| `InconsistentLocking` | 13 | 5 | 0 | 8 |
| `NullReturnDereference` | 5 | 1 | 4 | 0 |
| `NullPassedToDereferencedParameter` | 1 | 1 | 0 | 0 |
| `NegativeIndexFromSearch` | 2 | 0 | 2 | 0 |
| `SecretLeak` | 1 | 0 | 1 | 0 |
| other 42 rules | 0 | | | |

- True positives: fields read or written without the lock that guards them everywhere else (in commons-io a
  `long` read without the lock is not even atomic), `null` passed to a Jackson method that dereferences that
  parameter, a nullable `getAccessor()` result used without a check.
- False positives: `Map.get(CONSTANT)` on Kafka configuration maps that Kafka fills with defaults,
  `lastIndexOf`/`indexOf` results whose presence was established just before, a blank client secret put into an
  exception message.
- Not confirmed: Kafka coordinator and batch fields that Kafka documents as accessed from one thread.

The rules outside the default profile reported 9 issues on the same libraries, none of them a defect.
Before the precision work described above, the same run reported 840 issues.

## Known limits

- Taint analysis is context-insensitive and field-insensitive (except constant map keys); a value stored in a
  field of a tainted object and read back elsewhere is not followed.
- Lock identity is by class (`this` of a class, a field, or the declared type of a local), so two instances of one
  class are not told apart.
- Recursive call cycles are cut; their summaries may be incomplete.
- Methods annotated `@Nullable`/`@CheckForNull` are left to sonar-java's `S2259`.

## Build

```sh
./mvnw verify    # JDK 21
```

The plugin jar is `target/sonar-deep-java-<version>.jar`. `tools/benchmark` holds the benchmark harness.

## License

Apache-2.0
