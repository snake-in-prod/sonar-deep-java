package io.github.snakeinprod.deepjava.prepass;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the pre-pass learns from one class file. It depends only on the bytes of that class,
 * so it can be cached by class hash; facts that need other classes are solved later in {@link Summaries}.
 *
 * @param methods keyed by name + descriptor
 */
public record ClassFacts(String name, String superName, List<String> interfaces, String sourceFile, Map<String, MethodFacts> methods) {

  /**
   * @param nullReturns     line of each {@code return null} to the calls made last before it on some path; an empty
   *                        set means no call precedes it. The return is unreachable if all those calls never return.
   * @param onlyNull        every return statement returns the null constant (a default to be overridden)
   * @param returnsNormally the method has a return instruction (false: it always throws)
   * @param nullableCalls   call key (see {@link BytecodeScanner#callKey}) whose result may be returned, to the line of that return
   * @param invokedNames    names of all methods this method calls (used by library model guards)
   * @param callSites       per callee ({@link BytecodeScanner#statKey}), how many of its results this method uses
   * @param nullChecks      per callee, how many of those results this method tests for null
   */
  public record MethodFacts(boolean annotatedNullable, Map<Integer, Set<String>> nullReturns, boolean onlyNull, boolean returnsNormally,
    Map<String, Integer> nullableCalls, Set<String> invokedNames, Map<String, Integer> callSites, Map<String, Integer> nullChecks) {

    static MethodFacts empty(boolean annotated, boolean returnsNormally) {
      return new MethodFacts(annotated, Map.of(), false, returnsNormally, Map.of(), Set.of(), Map.of(), Map.of());
    }
  }

  void write(DataOutputStream out) throws IOException {
    out.writeUTF(name);
    out.writeUTF(superName == null ? "" : superName);
    out.writeUTF(sourceFile == null ? "" : sourceFile);
    writeStrings(out, interfaces);
    out.writeInt(methods.size());
    for (Map.Entry<String, MethodFacts> e : methods.entrySet()) {
      MethodFacts m = e.getValue();
      out.writeUTF(e.getKey());
      out.writeBoolean(m.annotatedNullable());
      out.writeBoolean(m.onlyNull());
      out.writeBoolean(m.returnsNormally());
      out.writeInt(m.nullReturns().size());
      for (Map.Entry<Integer, Set<String>> r : m.nullReturns().entrySet()) {
        out.writeInt(r.getKey());
        writeStrings(out, r.getValue());
      }
      writeCounts(out, m.nullableCalls());
      writeStrings(out, m.invokedNames());
      writeCounts(out, m.callSites());
      writeCounts(out, m.nullChecks());
    }
  }

  private static void writeCounts(DataOutputStream out, Map<String, Integer> counts) throws IOException {
    out.writeInt(counts.size());
    for (Map.Entry<String, Integer> c : counts.entrySet()) {
      out.writeUTF(c.getKey());
      out.writeInt(c.getValue());
    }
  }

  private static Map<String, Integer> readCounts(DataInputStream in) throws IOException {
    Map<String, Integer> m = new HashMap<>();
    for (int j = in.readInt(); j > 0; j--) {
      m.put(in.readUTF(), in.readInt());
    }
    return m;
  }

  static ClassFacts read(DataInputStream in) throws IOException {
    String name = in.readUTF();
    String superName = emptyToNull(in.readUTF());
    String sourceFile = emptyToNull(in.readUTF());
    List<String> interfaces = new ArrayList<>(readStrings(in));
    Map<String, MethodFacts> methods = new HashMap<>();
    for (int i = in.readInt(); i > 0; i--) {
      String key = in.readUTF();
      boolean annotated = in.readBoolean();
      boolean onlyNull = in.readBoolean();
      boolean returnsNormally = in.readBoolean();
      Map<Integer, Set<String>> nullReturns = new HashMap<>();
      for (int j = in.readInt(); j > 0; j--) {
        nullReturns.put(in.readInt(), readStrings(in));
      }
      Map<String, Integer> calls = readCounts(in);
      Set<String> invoked = readStrings(in);
      methods.put(key, new MethodFacts(annotated, nullReturns, onlyNull, returnsNormally, calls, invoked, readCounts(in), readCounts(in)));
    }
    return new ClassFacts(name, superName, interfaces, sourceFile, methods);
  }

  private static void writeStrings(DataOutputStream out, Collection<String> strings) throws IOException {
    out.writeInt(strings.size());
    for (String s : strings) {
      out.writeUTF(s);
    }
  }

  private static Set<String> readStrings(DataInputStream in) throws IOException {
    Set<String> s = new HashSet<>();
    for (int i = in.readInt(); i > 0; i--) {
      s.add(in.readUTF());
    }
    return s;
  }

  private static String emptyToNull(String s) {
    return s.isEmpty() ? null : s;
  }
}
