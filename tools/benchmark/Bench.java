import io.github.snakeinprod.deepjava.prepass.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Runs the pre-pass on a class directory and prints every finding as rule,path,line,message. */
public class Bench {
  public static void main(String[] a) throws Exception {
    List<ClassFacts> facts = new ArrayList<>();
    try (Stream<Path> s = Files.walk(Path.of(a[0]))) {
      for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".class"))::iterator) {
        facts.add(BytecodeScanner.scan(Files.readAllBytes(p), 20000));
      }
    }
    List<Path> libs = new ArrayList<>();
    if (a.length > 1) try (Stream<Path> s = Files.list(Path.of(a[1]))) { s.forEach(libs::add); }
    Summaries sum = new Summaries(facts, new Hierarchy(Summaries.byName(facts), libs), LibraryModels.load(List.of()), Summaries.Thresholds.DEFAULT);
    String[] rules = {"TaintedSql", "TaintedCommand", "TaintedPath", "TaintedUrl", "TaintedLdap", "TaintedXPath", "TaintedLog", "TaintedHeader",
      "TaintedRedirect", "TaintedExpression", "TaintedFormatString", "TaintedSize", "TaintedSecurityDecision", "SecretLeak",
      "IgnoredResultStatistical", "MissingSuperCall", "InconsistentLocking", "GuardedByViolation", "MixedLocks", "LockNotReleasedAcrossMethods",
      "NonReentrantLockReacquired", "StaticFieldInstanceLock", "LockOrderCycle", "UncheckedExceptionInEntryPoint", "UnreachableCatch",
      "CodeAfterAlwaysThrows", "DeadEnumCase", "EmptyMapLookup"};
    Set<String> paths = new TreeSet<>();
    facts.forEach(c -> paths.add(c.sourcePath()));
    for (String path : paths) {
      for (String r : rules) {
        for (Summaries.Finding f : sum.findings(r, path)) {
          System.out.println(r + "," + path + "," + f.line() + "," + f.message().replace(',', ';'));
        }
      }
    }
  }
}
