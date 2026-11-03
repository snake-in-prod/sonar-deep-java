package io.github.snakeinprod.deepjava.prepass;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the pre-pass learns from one class file. It depends only on the bytes of that class, so it is cached by
 * class hash; facts that need other classes are solved later in {@link Summaries}.
 *
 * <p>Call keys follow {@link BytecodeScanner#callKey}. Lock ids are {@code this}, {@code class:<type>},
 * {@code field:<owner>#<name>} or {@code static:<owner>#<name>}.
 *
 * @param annotations simple annotation name to its {@code value} (empty string if none)
 * @param methods     keyed by name + descriptor
 * @param fields      keyed by name
 */
public record ClassFacts(String name, String superName, List<String> interfaces, String sourceFile, int access,
  Map<String, String> annotations, Map<String, FieldFacts> fields, Map<String, MethodFacts> methods) implements Serializable {

  /** Source path relative to the source root, e.g. {@code com/acme/Foo.java}. */
  public String sourcePath() {
    int slash = name.lastIndexOf('/');
    String pkg = slash < 0 ? "" : name.substring(0, slash + 1);
    String file = sourceFile != null ? sourceFile : name.substring(slash + 1).replaceAll("\\$.*", "") + ".java";
    return pkg + file;
  }

  public record FieldFacts(int access, String desc, Map<String, String> annotations) implements Serializable {
  }

  /**
   * @param info  declaration facts
   * @param nulls nullness and return values
   * @param uses  how call results and parameters are used
   * @param locks synchronisation
   * @param ex    exceptions
   * @param taint data-flow summary for taint analysis
   */
  public record MethodFacts(Info info, NullFacts nulls, UseFacts uses, LockFacts locks, ExFacts ex, TaintFacts taint)
    implements Serializable {

    static MethodFacts empty(Info info, boolean returnsNormally) {
      return new MethodFacts(info, NullFacts.empty(returnsNormally), UseFacts.EMPTY, LockFacts.EMPTY, ExFacts.EMPTY, TaintFacts.EMPTY);
    }
  }

  /** @param paramNames from the LocalVariableTable, empty when compiled without {@code -g} */
  public record Info(int access, Map<String, String> annotations, List<String> paramNames, int firstLine) implements Serializable {
  }

  /**
   * @param annotatedNullable return annotated {@code @Nullable}/{@code @CheckForNull}
   * @param nullReturns       line of each {@code return null} to the calls made last before it on some path (empty:
   *                          none); the return is unreachable if all those calls never return
   * @param onlyNull          every return statement returns the null constant (a default meant to be overridden)
   * @param returnsNormally   the method has a return instruction (false: it always throws)
   * @param returnedCalls     call key whose result may be returned, to the line of that return
   * @param invokedNames      names of all methods this method calls (used by library model guards)
   * @param constantReturn    the single constant every return returns ({@code "I:0"}, {@code "S:text"}, {@code "null"}), or null
   * @param mayReturnMinusOne some path returns the int constant -1
   * @param returnedNew       types instantiated with {@code new} and returned
   */
  public record NullFacts(boolean annotatedNullable, Map<Integer, Set<String>> nullReturns, boolean onlyNull,
    boolean returnsNormally, Map<String, Integer> returnedCalls, Set<String> invokedNames, String constantReturn,
    boolean mayReturnMinusOne, Set<String> returnedNew) implements Serializable {

    static NullFacts empty(boolean returnsNormally) {
      return new NullFacts(false, Map.of(), false, returnsNormally, Map.of(), Set.of(), null, false, Set.of());
    }
  }

  /**
   * @param callSites       per callee ({@link BytecodeScanner#statKey}), how many of its reference results this method uses
   * @param nullChecks      per callee, how many of those results this method tests for null
   * @param valueSites      per callee returning any value, how many results are used
   * @param ignoredResults  per callee returning any value, lines where the result is discarded
   * @param derefParams     parameters dereferenced on every path that returns normally
   * @param closedParams    parameters whose {@code close()} is called
   * @param callsSuper      the method calls the method it overrides ({@code super.m()})
   * @param staticWrites    static fields written, to a line
   * @param fieldWrites     fields (owner#name) written outside constructors / static initialisers
   * @param fieldEscapes    fields (owner#name) read and passed on, returned or stored (not only used as a receiver)
   * @param fieldCalls      per field, names of the methods called on it
   * @param fieldInits      per field written in a constructor or static initialiser, the constant stored
   *                        ({@code "I:0"}...) or {@code "?"} for anything else
   * @param enumLoads       enum constants (owner#name) loaded, excluding switch-map initialisers
   * @param switchMapLoads  enum constants loaded by a switch-map initialiser, to a line
   */
  public record UseFacts(Map<String, Integer> callSites, Map<String, Integer> nullChecks, Map<String, Integer> valueSites,
    Map<String, List<Integer>> ignoredResults, Set<Integer> derefParams, Set<Integer> closedParams, boolean callsSuper,
    Map<String, Integer> staticWrites, Set<String> fieldWrites, Set<String> fieldEscapes, Map<String, Set<String>> fieldCalls,
    Map<String, String> fieldInits, Set<String> enumLoads, Map<String, Integer> switchMapLoads) implements Serializable {

    static final UseFacts EMPTY = new UseFacts(Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), false, Map.of(),
      Set.of(), Set.of(), Map.of(), Map.of(), Set.of(), Map.of());
  }

  public record FieldAccess(String field, boolean write, int line, Set<String> locks) implements Serializable {
  }

  public record LockEdge(String held, String acquired, int line) implements Serializable {
  }

  public record LockedCall(String callKey, Set<String> locks, int line) implements Serializable {
  }

  /**
   * @param accesses     instance and static field accesses with the locks held
   * @param edges        lock acquired while another is held
   * @param lockedCalls  calls made while holding a lock
   * @param acquired     explicit {@code Lock.lock()} acquisitions (not monitors), to a line
   * @param heldAtReturn explicit locks still held on some normal return
   * @param reacquired   non-reentrant locks acquired again while held, to a line
   */
  public record LockFacts(List<FieldAccess> accesses, List<LockEdge> edges, List<LockedCall> lockedCalls,
    Map<String, Integer> acquired, Set<String> heldAtReturn, Map<String, Integer> reacquired) implements Serializable {

    static final LockFacts EMPTY = new LockFacts(List.of(), List.of(), List.of(), Map.of(), Set.of(), Map.of());
  }

  /** @param caught handler types covering the call */
  public record CallSite(String callKey, int line, Set<String> caught, int nextLine, boolean nextIsTrivial) implements Serializable {
  }

  public record ThrowSite(String type, int line, Set<String> caught) implements Serializable {
  }

  /** A catch handler and what its try range calls. */
  public record TryFacts(String handlerType, int line, Set<String> callKeys, boolean hasLambda) implements Serializable {
  }

  public record ExFacts(List<CallSite> calls, List<ThrowSite> throwsNew, List<TryFacts> tries) implements Serializable {
    static final ExFacts EMPTY = new ExFacts(List.of(), List.of(), List.of());
  }

  /**
   * Labels: {@code P<i>} parameter i, {@code S<kind>} source, {@code C<site>} result of call edge {@code site},
   * {@code F<owner#name>} field value. A {@code #} suffix marks a numeric value (only size sinks apply).
   *
   * @param returns labels that can reach a return
   * @param sinks   sinks reached
   * @param edges   calls to resolve against callee summaries
   */
  public record TaintFacts(Set<String> returns, List<Sink> sinks, List<Edge> edges) implements Serializable {
    static final TaintFacts EMPTY = new TaintFacts(Set.of(), List.of(), List.of());
  }

  public record Sink(String kind, int line, Set<String> labels, String api) implements Serializable {
  }

  /** @param args labels of each argument, receiver first for instance calls */
  public record Edge(int site, String callKey, List<Set<String>> args, int line) implements Serializable {
  }
}
