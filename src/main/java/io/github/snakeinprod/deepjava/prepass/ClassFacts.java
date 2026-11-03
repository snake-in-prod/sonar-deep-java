package io.github.snakeinprod.deepjava.prepass;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
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
   * @param nullReturnLine line of a {@code return null} path, or -1
   * @param nullableCalls  call key (see {@link BytecodeScanner#callKey}) whose result may be returned, to the line of that return
   * @param invokedNames   names of all methods this method calls (used by library model guards)
   */
  public record MethodFacts(boolean annotatedNullable, int nullReturnLine, Map<String, Integer> nullableCalls, Set<String> invokedNames) {
    static final MethodFacts NONE = new MethodFacts(false, -1, Map.of(), Set.of());
  }

  void write(DataOutputStream out) throws IOException {
    out.writeUTF(name);
    out.writeUTF(superName == null ? "" : superName);
    out.writeUTF(sourceFile == null ? "" : sourceFile);
    out.writeInt(interfaces.size());
    for (String i : interfaces) {
      out.writeUTF(i);
    }
    out.writeInt(methods.size());
    for (Map.Entry<String, MethodFacts> e : methods.entrySet()) {
      MethodFacts m = e.getValue();
      out.writeUTF(e.getKey());
      out.writeBoolean(m.annotatedNullable());
      out.writeInt(m.nullReturnLine());
      out.writeInt(m.nullableCalls().size());
      for (Map.Entry<String, Integer> c : m.nullableCalls().entrySet()) {
        out.writeUTF(c.getKey());
        out.writeInt(c.getValue());
      }
      out.writeInt(m.invokedNames().size());
      for (String n : m.invokedNames()) {
        out.writeUTF(n);
      }
    }
  }

  static ClassFacts read(DataInputStream in) throws IOException {
    String name = in.readUTF();
    String superName = emptyToNull(in.readUTF());
    String sourceFile = emptyToNull(in.readUTF());
    List<String> interfaces = new ArrayList<>();
    for (int i = in.readInt(); i > 0; i--) {
      interfaces.add(in.readUTF());
    }
    Map<String, MethodFacts> methods = new HashMap<>();
    for (int i = in.readInt(); i > 0; i--) {
      String key = in.readUTF();
      boolean annotated = in.readBoolean();
      int line = in.readInt();
      Map<String, Integer> calls = new HashMap<>();
      for (int j = in.readInt(); j > 0; j--) {
        calls.put(in.readUTF(), in.readInt());
      }
      Set<String> names = new HashSet<>();
      for (int j = in.readInt(); j > 0; j--) {
        names.add(in.readUTF());
      }
      methods.put(key, new MethodFacts(annotated, line, calls, names));
    }
    return new ClassFacts(name, superName, interfaces, sourceFile, methods);
  }

  private static String emptyToNull(String s) {
    return s.isEmpty() ? null : s;
  }
}
