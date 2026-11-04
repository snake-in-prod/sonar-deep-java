package io.github.snakeinprod.deepjava.prepass;

import io.github.snakeinprod.deepjava.prepass.ClassFacts.Edge;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.Sink;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.TaintFacts;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

/**
 * Per-method taint data flow (see {@link TaintFacts} for labels). Values carry the parameters, sources and call
 * results they may come from; sinks and calls are recorded with those labels and resolved project-wide by
 * {@link TaintSolver}.
 */
public final class TaintScanner implements Opcodes {

  private TaintScanner() {
  }

  static TaintFacts analyze(ClassNode cn, MethodNode m, int maxSteps) {
    return analyze(cn, m, maxSteps, TaintModels.defaults());
  }

  static TaintFacts analyze(ClassNode cn, MethodNode m, int maxSteps, TaintModels models) {
    try {
      return new Flow(cn, m, maxSteps, models).run();
    } catch (AnalyzerException | RuntimeException e) {
      return TaintFacts.EMPTY;
    }
  }

  /** Tainted value: labels, local it was loaded from (or -1), origin ({@code new@i}, {@code S:text}, {@code I:n}, {@code static:o#n}). */
  record TV(int size, Set<String> labels, int local, String origin) implements Value {
    static final TV C1 = new TV(1, Set.of(), -1, null);
    static final TV C2 = new TV(2, Set.of(), -1, null);

    @Override
    public int getSize() {
      return size;
    }

    TV with(Set<String> l) {
      return new TV(size, l, local, origin);
    }
  }

  static Set<String> union(Set<String> a, Set<String> b) {
    if (a.isEmpty()) {
      return b;
    }
    if (b.isEmpty() || a.containsAll(b)) {
      return a;
    }
    Set<String> u = new HashSet<>(a);
    u.addAll(b);
    return Set.copyOf(u);
  }

  static Set<String> numeric(Set<String> labels) {
    Set<String> out = new HashSet<>();
    labels.forEach(l -> out.add(l.endsWith("#") ? l : l + "#"));
    return Set.copyOf(out);
  }

  private static final class Flow {
    private final ClassNode cn;
    private final MethodNode m;
    private final int maxSteps;
    private final TaintModels models;
    private final InsnList insns;
    private final Frame<TV>[] frames;
    private final boolean[] queued;
    private final int[] lines;
    private final List<TryCatchBlockNode>[] handlers;
    private final Deque<Integer> work = new ArrayDeque<>();
    private final Set<Integer> counters = new HashSet<>();
    private final Interp interp = new Interp();
    private final Map<Integer, Sink> sinks = new HashMap<>();
    private final Map<Integer, Edge> edges = new HashMap<>();
    private final Set<String> returns = new HashSet<>();
    private final String file;
    private int current;

    @SuppressWarnings("unchecked")
    Flow(ClassNode cn, MethodNode m, int maxSteps, TaintModels models) {
      this.cn = cn;
      this.m = m;
      this.maxSteps = maxSteps;
      this.models = models;
      this.insns = m.instructions;
      int n = insns.size();
      frames = new Frame[n];
      queued = new boolean[n];
      lines = new int[n];
      handlers = new List[n];
      file = cn.sourceFile != null ? cn.sourceFile : cn.name.substring(cn.name.lastIndexOf('/') + 1) + ".java";
      int line = -1;
      for (int i = 0; i < n; i++) {
        AbstractInsnNode insn = insns.get(i);
        if (insn instanceof LineNumberNode ln) {
          line = ln.line;
        }
        if (insn instanceof IincInsnNode inc) {
          counters.add(inc.var);
        }
        lines[i] = line;
      }
      for (TryCatchBlockNode tcb : m.tryCatchBlocks) {
        for (int i = insns.indexOf(tcb.start); i < insns.indexOf(tcb.end); i++) {
          if (handlers[i] == null) {
            handlers[i] = new ArrayList<>();
          }
          handlers[i].add(tcb);
        }
      }
    }

    private String source(String kind, String what) {
      return "S" + kind + "|" + what + "|" + file + ":" + lines[current];
    }

    TaintFacts run() throws AnalyzerException {
      Frame<TV> init = new Frame<>(m.maxLocals, m.maxStack);
      boolean instance = (m.access & ACC_STATIC) == 0;
      int slot = 0;
      int label = 0;
      if (instance) {
        init.setLocal(slot++, new TV(1, Set.of("P" + label++), -1, null));
      }
      Type[] args = Type.getArgumentTypes(m.desc);
      Set<String> methodAnnotations = BytecodeScanner.annotations(m.visibleAnnotations, m.invisibleAnnotations).keySet();
      boolean listener = methodAnnotations.stream().anyMatch(models::isListenerAnnotation) || ("onMessage".equals(m.name) && args.length == 1);
      boolean main = !instance && "main".equals(m.name) && "([Ljava/lang/String;)V".equals(m.desc);
      int line = -1;
      for (AbstractInsnNode insn : insns) {
        if (insn instanceof LineNumberNode ln) {
          line = ln.line;
          break;
        }
      }
      String where = file + ":" + line;
      List<String> names = paramNames();
      for (int p = 0; p < args.length; p++) {
        Set<String> labels = new HashSet<>();
        labels.add("P" + label++);
        String kind = paramSource(p);
        if (kind == null && listener) {
          kind = "MSG";
        }
        if (kind == null && main) {
          kind = "CMDLINE";
        }
        if (kind != null) {
          labels.add("S" + kind + "|parameter \"" + nameOf(names, p) + "\" of " + m.name + "()|" + where);
        }
        if (models.isSecretName(nameOf(names, p)) && isSecretType(args[p])) {
          labels.add("SSECRET|parameter \"" + nameOf(names, p) + "\"|" + where);
        }
        if (args[p].getSort() != Type.OBJECT && args[p].getSort() != Type.ARRAY) {
          labels = numeric(labels);
        }
        init.setLocal(slot++, new TV(args[p].getSize(), Set.copyOf(labels), -1, null));
        if (args[p].getSize() == 2) {
          init.setLocal(slot++, TV.C1);
        }
      }
      while (slot < m.maxLocals) {
        init.setLocal(slot++, TV.C1);
      }
      flow(0, init);
      int steps = 0;
      while (!work.isEmpty()) {
        if (++steps > maxSteps) {
          return TaintFacts.EMPTY;
        }
        int i = work.pop();
        queued[i] = false;
        step(i, frames[i]);
      }
      return new TaintFacts(Set.copyOf(returns), List.copyOf(sinks.values()), List.copyOf(edges.values()));
    }

    /** Passwords, keys and tokens are text or bytes; an "apiKey" of another type is an id. */
    static boolean isSecretType(Type t) {
      String d = t.getDescriptor();
      return d.equals("Ljava/lang/String;") || d.equals("[C") || d.equals("[B") || d.equals("Ljava/lang/CharSequence;");
    }

    private static String nameOf(List<String> names, int p) {
      return p < names.size() && !names.get(p).isEmpty() ? names.get(p) : "arg" + p;
    }

    private List<String> paramNames() {
      List<String> out = new ArrayList<>();
      int slot = (m.access & ACC_STATIC) == 0 ? 1 : 0;
      for (Type t : Type.getArgumentTypes(m.desc)) {
        String name = "";
        if (m.localVariables != null) {
          for (var lv : m.localVariables) {
            if (lv.index == slot) {
              name = lv.name;
              break;
            }
          }
        }
        out.add(name);
        slot += t.getSize();
      }
      return out;
    }

    private String paramSource(int p) {
      for (List<AnnotationNode>[] all : List.of(nullToEmpty(m.visibleParameterAnnotations), nullToEmpty(m.invisibleParameterAnnotations))) {
        if (p < all.length && all[p] != null) {
          for (AnnotationNode a : all[p]) {
            String simple = a.desc.substring(Math.max(a.desc.lastIndexOf('/'), a.desc.lastIndexOf('$')) + 1, a.desc.length() - 1);
            String kind = models.parameterAnnotationSource(simple);
            if (kind != null) {
              return kind;
            }
          }
        }
      }
      return null;
    }

    @SuppressWarnings("unchecked")
    private static List<AnnotationNode>[] nullToEmpty(List<AnnotationNode>[] a) {
      return a == null ? new List[0] : a;
    }

    static Integer intConst(TV v) {
      String o = v.origin();
      if (o != null && o.startsWith("I:")) {
        try {
          return Integer.valueOf(o.substring(2));
        } catch (NumberFormatException e) {
          return null;
        }
      }
      return null;
    }

    static String keyOf(TV v) {
      String o = v.origin();
      return o != null && (o.startsWith("S:") || o.startsWith("I:")) ? o : null;
    }

    /** Keyed labels are {@code K<key>~<label>}; a constant key selects its own, any other key sees all. */
    static Set<String> mapGet(Set<String> labels, String key) {
      Set<String> out = new HashSet<>();
      for (String l : labels) {
        if (l.startsWith("K")) {
          int sep = l.indexOf('~');
          if (key == null || l.substring(1, sep).equals(key)) {
            out.add(l.substring(sep + 1));
          }
        } else {
          out.add(l);
        }
      }
      return Set.copyOf(out);
    }

    static Set<String> unkeyed(Set<String> labels) {
      return labels.stream().anyMatch(l -> l.startsWith("K")) ? mapGet(labels, null) : labels;
    }

    /** Source kind of a field of this class annotated as a source (e.g. Spring {@code @Value}), or null. */
    private String fieldSource(FieldInsnNode f) {
      if (!f.owner.equals(cn.name)) {
        return null;
      }
      for (org.objectweb.asm.tree.FieldNode fn : cn.fields) {
        if (fn.name.equals(f.name)) {
          for (String a : BytecodeScanner.annotations(fn.visibleAnnotations, fn.invisibleAnnotations).keySet()) {
            String kind = models.fieldAnnotationSource(a);
            if (kind != null) {
              return kind;
            }
          }
        }
      }
      return null;
    }

    private void sink(String kind, Set<String> labels, String api) {
      labels = unkeyed(labels);
      if (!labels.isEmpty()) {
        sinks.merge(current, new Sink(kind, lines[current], labels, api),
          (a, b) -> new Sink(a.kind(), a.line(), union(a.labels(), b.labels()), a.api()));
      }
    }

    private static String display(String owner, String name) {
      String simple = owner.substring(owner.lastIndexOf('/') + 1).replace('$', '.');
      return simple + "." + ("<init>".equals(name) ? "new" : name) + "()";
    }

    final class Interp extends Interpreter<TV> {
      Interp() {
        super(ASM9);
      }

      @Override
      public TV newValue(Type type) {
        if (type == Type.VOID_TYPE) {
          return null;
        }
        return type != null && type.getSize() == 2 ? TV.C2 : TV.C1;
      }

      @Override
      public TV newOperation(AbstractInsnNode insn) {
        switch (insn.getOpcode()) {
          case LCONST_0, LCONST_1, DCONST_0, DCONST_1:
            return TV.C2;
          case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5:
            return new TV(1, Set.of(), -1, "I:" + (insn.getOpcode() - ICONST_0));
          case BIPUSH, SIPUSH:
            return new TV(1, Set.of(), -1, "I:" + ((IntInsnNode) insn).operand);
          case LDC:
            Object c = ((LdcInsnNode) insn).cst;
            if (c instanceof Long || c instanceof Double) {
              return TV.C2;
            }
            return new TV(1, Set.of(), -1, c instanceof String s ? "S:" + s : c instanceof Integer i ? "I:" + i : null);
          case GETSTATIC:
            FieldInsnNode f = (FieldInsnNode) insn;
            Type t = Type.getType(f.desc);
            String fieldKind = models.source(f.owner, f.name);
            Set<String> l = fieldKind != null ? Set.of(source(fieldKind, f.owner.substring(f.owner.lastIndexOf('/') + 1) + "." + f.name))
              : models.isSecretName(f.name) && isSecretType(t) && !"java/lang/System".equals(f.owner)
              ? Set.of(source("SECRET", "field \"" + f.name + "\"")) : Set.of();
            return new TV(t.getSize(), l, -1, "static:" + f.owner + "#" + f.name);
          case NEW:
            return new TV(1, Set.of(), -1, "new@" + current);
          default:
            return TV.C1;
        }
      }

      @Override
      public TV copyOperation(AbstractInsnNode insn, TV v) {
        int op = insn.getOpcode();
        if (op >= ILOAD && op <= ALOAD) {
          return new TV(v.size(), v.labels(), ((VarInsnNode) insn).var, v.origin());
        }
        if (op >= ISTORE && op <= ASTORE) {
          return new TV(v.size(), v.labels(), -1, v.origin());
        }
        return v;
      }

      @Override
      public TV unaryOperation(AbstractInsnNode insn, TV v) {
        int op = insn.getOpcode();
        switch (op) {
          case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE, TABLESWITCH, LOOKUPSWITCH, IRETURN, LRETURN, FRETURN, DRETURN, ARETURN,
            PUTSTATIC, ATHROW, MONITORENTER, MONITOREXIT, IFNULL, IFNONNULL:
            return null;
          case GETFIELD:
            FieldInsnNode f = (FieldInsnNode) insn;
            Type t = Type.getType(f.desc);
            Set<String> l = v.labels();
            String annotated = fieldSource(f);
            if (annotated != null) {
              l = union(l, Set.of(source(annotated, "field \"" + f.name + "\"")));
            }
            if (models.isSecretName(f.name) && isSecretType(t)) {
              l = union(l, Set.of(source("SECRET", "field \"" + f.name + "\"")));
            }
            return new TV(t.getSize(), BytecodeScanner.isRef(t) ? l : numeric(l), -1, null);
          case NEWARRAY, ANEWARRAY:
            sink("SIZE", v.labels(), "array allocation");
            return new TV(1, Set.of(), -1, "new@" + current);
          case ARRAYLENGTH:
            return TV.C1;
          case I2L, I2D, I2F, L2I, L2F, L2D, F2I, F2L, F2D, D2I, D2L, D2F, I2B, I2C, I2S, INEG, LNEG, FNEG, DNEG:
            Type r = switch (op) {
              case I2L, F2L, D2L, LNEG, I2D, L2D, F2D, DNEG -> Type.LONG_TYPE;
              default -> Type.INT_TYPE;
            };
            return new TV(r.getSize(), numeric(v.labels()), -1, null);
          case CHECKCAST:
            return v;
          case INSTANCEOF:
            return TV.C1;
          default:
            return TV.C1;
        }
      }

      @Override
      public TV binaryOperation(AbstractInsnNode insn, TV v1, TV v2) {
        int op = insn.getOpcode();
        switch (op) {
          case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE, PUTFIELD:
            return null;
          case IALOAD, BALOAD, CALOAD, SALOAD, FALOAD, AALOAD, LALOAD, DALOAD:
            sink("SIZE", v2.labels(), "array index");
            int size = op == LALOAD || op == DALOAD ? 2 : 1;
            return new TV(size, op == AALOAD ? v1.labels() : numeric(v1.labels()), -1, null);
          case LADD, LSUB, LMUL, LDIV, LREM, LSHL, LSHR, LUSHR, LAND, LOR, LXOR, DADD, DSUB, DMUL, DDIV, DREM:
            return new TV(2, numeric(union(v1.labels(), v2.labels())), -1, null);
          case LCMP, FCMPL, FCMPG, DCMPL, DCMPG:
            return TV.C1;
          default:
            return new TV(1, numeric(union(v1.labels(), v2.labels())), -1, fold(insn.getOpcode(), v1, v2));
        }
      }

      /** Constant result of int arithmetic on two constants (for branch pruning), or null. */
      private String fold(int op, TV a, TV b) {
        Integer x = intConst(a);
        Integer y = intConst(b);
        if (x == null || y == null) {
          return null;
        }
        Integer r = switch (op) {
          case IADD -> x + y;
          case ISUB -> x - y;
          case IMUL -> x * y;
          case IDIV -> y == 0 ? null : x / y;
          case IREM -> y == 0 ? null : x % y;
          case IAND -> x & y;
          case IOR -> x | y;
          case IXOR -> x ^ y;
          default -> null;
        };
        return r == null ? null : "I:" + r;
      }

      @Override
      public TV ternaryOperation(AbstractInsnNode insn, TV v1, TV v2, TV v3) {
        if (insn.getOpcode() >= IASTORE && insn.getOpcode() <= SASTORE) {
          sink("SIZE", v2.labels(), "array index");
        }
        return null;
      }

      @Override
      public TV naryOperation(AbstractInsnNode insn, List<? extends TV> values) {
        if (insn instanceof InvokeDynamicInsnNode indy) {
          Type ret = Type.getReturnType(indy.desc);
          Set<String> l = Set.of();
          if (indy.name.startsWith("makeConcat")) {
            // numbers stay numeric: "id=" + n cannot inject
            for (TV v : values) {
              l = union(l, v.labels());
            }
          }
          return ret == Type.VOID_TYPE ? null : new TV(ret.getSize(), l, -1, null);
        }
        if (!(insn instanceof MethodInsnNode mi)) {
          return TV.C1;
        }
        Type ret = Type.getReturnType(mi.desc);
        boolean instance = mi.getOpcode() != INVOKESTATIC;
        int first = instance ? 1 : 0;
        TV receiver = instance ? values.get(0) : null;
        Set<String> argLabels = Set.of();
        for (int a = first; a < values.size(); a++) {
          argLabels = union(argLabels, values.get(a).labels());
        }

        TaintModels.SinkModel sink = models.sink(mi.owner, mi.name);
        if (sink != null) {
          Set<String> l = Set.of();
          for (int idx : sink.args()) {
            int v = idx < 0 ? 0 : idx + first;
            if (v < values.size() && (idx >= 0 || instance)) {
              l = union(l, values.get(v).labels());
            }
          }
          if ("DECISION".equals(sink.kind()) && values.stream().noneMatch(v -> v.origin() != null && v.origin().startsWith("S:"))
            && !"parseBoolean".equals(mi.name)) {
            // only a comparison with a constant ("admin") decides on the cookie alone
            l = Set.of();
          }
          if ("STDOUT".equals(sink.kind()) && (receiver == null || receiver.origin() == null || !receiver.origin().startsWith("static:java/lang/System#"))) {
            l = Set.of();
          }
          sink(sink.kind(), l, display(mi.owner, mi.name));
          Set<String> pl = Set.of();
          for (int idx : sink.parameterArgs()) {
            int v = idx + first;
            if (v < values.size()) {
              pl = union(pl, values.get(v).labels());
            }
          }
          sink(sink.kind() + "ARG", pl, display(mi.owner, mi.name));
        }
        // only "new X(secret)": super(...) inside exception constructors would repeat the same flow
        if ("<init>".equals(mi.name) && mi.owner.matches(".*(Exception|Error)") && !argLabels.isEmpty()
          && receiver != null && receiver.origin() != null && receiver.origin().startsWith("new@")) {
          sink("EXCEPTION", argLabels, "new " + mi.owner.substring(mi.owner.lastIndexOf('/') + 1) + "()");
        }
        if (ret == Type.VOID_TYPE) {
          recordEdge(mi, values);
          return null;
        }
        if ("java/lang/String".equals(mi.owner) && receiver != null && receiver.origin() != null && receiver.origin().startsWith("S:")) {
          String text = receiver.origin().substring(2);
          if ("charAt".equals(mi.name) && intConst(values.get(1)) != null && intConst(values.get(1)) >= 0 && intConst(values.get(1)) < text.length()) {
            return new TV(1, Set.of(), -1, "I:" + (int) text.charAt(intConst(values.get(1))));
          }
          if ("length".equals(mi.name)) {
            return new TV(1, Set.of(), -1, "I:" + text.length());
          }
        }
        // a map read with a constant key only sees what was put under that key
        if ("get".equals(mi.name) && receiver != null && values.size() == 2 && mi.owner.matches("java/util/(Map|HashMap|LinkedHashMap|TreeMap|Hashtable)")) {
          String key = keyOf(values.get(1));
          return new TV(1, mapGet(receiver.labels(), key), -1, null);
        }
        String kind = models.source(mi.owner, mi.name);
        if (kind != null && !(kind.equals("SECRET") && !isSecretType(ret))) {
          // a source read from a tainted object keeps that taint too (readLine() of a socket reader)
          Set<String> src = union(Set.of(source(kind, display(mi.owner, mi.name))), receiver == null ? Set.of() : receiver.labels());
          return new TV(ret.getSize(), BytecodeScanner.isRef(ret) ? src : numeric(src), -1, null);
        }
        if (models.isSanitizer(mi.owner, mi.name) || isCrlfStrip(mi, values)) {
          return newValue(ret);
        }
        String p = models.propagation(mi.owner, mi.name);
        if (p != null) {
          Set<String> l = switch (p) {
            case "receiver" -> receiver == null ? argLabels : receiver.labels();
            case "args" -> argLabels;
            default -> union(receiver == null ? Set.of() : receiver.labels(), argLabels);
          };
          if (!"numeric".equals(p) && !BytecodeScanner.isRef(ret) && !"all".equals(p) && !"args".equals(p)) {
            // size(), charAt(), hashCode() of a tainted container or string: bounded by real data
            l = Set.of();
          } else if ("numeric".equals(p) || !BytecodeScanner.isRef(ret)) {
            l = numeric(l);
          } else if (Type.getType(String.class).equals(ret)) {
            l = Set.copyOf(l.stream().map(x -> x.endsWith("#") ? x.substring(0, x.length() - 1) : x).toList());
          }
          return new TV(ret.getSize(), l, -1, null);
        }
        recordEdge(mi, values);
        String c = "C" + current;
        return new TV(ret.getSize(), Set.of(BytecodeScanner.isRef(ret) ? c : c + "#"), -1, null);
      }

      private boolean isCrlfStrip(MethodInsnNode mi, List<? extends TV> values) {
        return isCrlfStrip0(mi, values);
      }

      private boolean isCrlfStrip0(MethodInsnNode mi, List<? extends TV> values) {
        if (!"java/lang/String".equals(mi.owner) || !mi.name.startsWith("replace")) {
          return false;
        }
        for (TV v : values) {
          String o = v.origin();
          if (o != null && o.startsWith("S:") && (o.contains("\n") || o.contains("\r") || o.contains("\\n") || o.contains("\\r"))) {
            return true;
          }
        }
        return false;
      }

      private void recordEdge(MethodInsnNode mi, List<? extends TV> values) {
        if (mi.owner.startsWith("java/") || mi.owner.startsWith("javax/") || mi.owner.startsWith("jakarta/") || mi.owner.startsWith("sun/")) {
          return;
        }
        List<Set<String>> args = new ArrayList<>();
        for (TV v : values) {
          args.add(unkeyed(v.labels()));
        }
        boolean exact = mi.getOpcode() == INVOKESTATIC || mi.getOpcode() == INVOKESPECIAL;
        String key = BytecodeScanner.callKey(exact, mi.owner, mi.name, mi.desc);
        edges.merge(current, new Edge(current, key, List.copyOf(args), lines[current]), (a, b) -> {
          List<Set<String>> merged = new ArrayList<>();
          for (int i = 0; i < a.args().size(); i++) {
            merged.add(union(a.args().get(i), b.args().get(i)));
          }
          return new Edge(a.site(), a.callKey(), List.copyOf(merged), a.line());
        });
      }

      @Override
      public void returnOperation(AbstractInsnNode insn, TV value, TV expected) {
        returns.addAll(unkeyed(value.labels()));
      }

      @Override
      public TV merge(TV v, TV w) {
        if (v.equals(w)) {
          return v;
        }
        if (v.size() != w.size()) {
          return TV.C1;
        }
        return new TV(v.size(), union(v.labels(), w.labels()), v.local() == w.local() ? v.local() : -1,
          v.origin() != null && v.origin().equals(w.origin()) ? v.origin() : null);
      }
    }

    private void step(int i, Frame<TV> f) throws AnalyzerException {
      current = i;
      AbstractInsnNode insn = insns.get(i);
      int op = insn.getOpcode();
      if (op < 0) {
        flow(i + 1, f);
        return;
      }
      if (handlers[i] != null) {
        for (TryCatchBlockNode tcb : handlers[i]) {
          Frame<TV> h = new Frame<>(f);
          h.clearStack();
          h.push(TV.C1);
          flow(insns.indexOf(tcb.handler), h);
        }
      }
      if (op == JSR || op == RET) {
        throw new AnalyzerException(insn, "JSR/RET not supported");
      }
      int sp = f.getStackSize();
      Frame<TV> out = new Frame<>(f);
      out.execute(insn, interp);

      if (insn instanceof MethodInsnNode mi) {
        int argc = Type.getArgumentTypes(mi.desc).length;
        boolean instance = op != INVOKESTATIC;
        TV receiver = instance ? f.getStack(sp - 1 - argc) : null;
        Set<String> argLabels = Set.of();
        for (int a = 0; a < argc; a++) {
          argLabels = union(argLabels, f.getStack(sp - 1 - a).labels());
        }
        String p = models.propagation(mi.owner, mi.name);
        boolean mutates = ("append".equals(mi.name) || "insert".equals(mi.name)) && "all".equals(p)
          || p != null && Set.of("add", "addAll", "put", "putAll", "set", "offer", "push", "addFirst", "addLast", "putIfAbsent", "command", "load").contains(mi.name)
          || "<init>".equals(mi.name) && ("args".equals(p) || "all".equals(p) || models.propagation(mi.owner, "<init>") != null);
        String ctorSource = "<init>".equals(mi.name) ? models.source(mi.owner, "<init>") : null;
        if (receiver != null && ctorSource != null) {
          taintObject(out, receiver, Set.of(source(ctorSource, "new " + mi.owner.substring(mi.owner.lastIndexOf('/') + 1) + "()")));
        }
        if (receiver != null && mutates && !argLabels.isEmpty()) {
          if ("put".equals(mi.name) && argc == 2 && keyOf(f.getStack(sp - 2)) != null) {
            String key = keyOf(f.getStack(sp - 2));
            Set<String> keyed = new HashSet<>();
            f.getStack(sp - 1).labels().forEach(l -> keyed.add("K" + key + "~" + l));
            taintObject(out, receiver, Set.copyOf(keyed));
          } else {
            taintObject(out, receiver, argLabels);
          }
        }
        // allow-list and prefix checks clear the checked value
        if (receiver != null && (Set.of("equals", "matches", "equalsIgnoreCase").contains(mi.name) && mi.owner.equals("java/lang/String")
          || "startsWith".equals(mi.name) && mi.owner.equals("java/nio/file/Path"))) {
          clear(out, receiver);
        }
        if ("contains".equals(mi.name) && argc == 1 && (mi.owner.startsWith("java/util/") && mi.owner.matches(".*(Set|List|Collection)"))) {
          clear(out, f.getStack(sp - 1));
        }
      }

      if (op == AASTORE && !f.getStack(sp - 1).labels().isEmpty()) {
        taintObject(out, f.getStack(sp - 3), f.getStack(sp - 1).labels());
      }

      if (insn instanceof JumpInsnNode j) {
        Frame<TV> next = out;
        if (op >= IF_ICMPEQ && op <= IF_ICMPLE) {
          TV a = f.getStack(sp - 2);
          TV b = f.getStack(sp - 1);
          next = bound(out, a, b);
          next = bound(next, b, a);
        }
        Boolean taken = constantJump(op, f, sp);
        if (taken == null || taken) {
          flow(insns.indexOf(j.label), next);
        }
        if (op != GOTO && (taken == null || !taken)) {
          flow(i + 1, next);
        }
      } else if (insn instanceof TableSwitchInsnNode ts) {
        Integer key = Flow.intConst(f.getStack(sp - 1));
        if (key == null) {
          flowAll(ts.dflt, ts.labels, out);
        } else {
          flow(insns.indexOf(key >= ts.min && key <= ts.max ? ts.labels.get(key - ts.min) : ts.dflt), out);
        }
      } else if (insn instanceof LookupSwitchInsnNode ls) {
        Integer key = Flow.intConst(f.getStack(sp - 1));
        if (key == null) {
          flowAll(ls.dflt, ls.labels, out);
        } else {
          int idx = ls.keys.indexOf(key);
          flow(insns.indexOf(idx >= 0 ? ls.labels.get(idx) : ls.dflt), out);
        }
      } else if (!(op >= IRETURN && op <= RETURN) && op != ATHROW) {
        flow(i + 1, out);
      }
    }

    /** Whether a conditional jump on constants is taken, or null when not constant. */
    private static Boolean constantJump(int op, Frame<TV> f, int sp) {
      if (op >= IFEQ && op <= IFLE) {
        Integer x = intConst(f.getStack(sp - 1));
        if (x == null) {
          return null;
        }
        return switch (op) {
          case IFEQ -> x == 0;
          case IFNE -> x != 0;
          case IFLT -> x < 0;
          case IFGE -> x >= 0;
          case IFGT -> x > 0;
          default -> x <= 0;
        };
      }
      if (op >= IF_ICMPEQ && op <= IF_ICMPLE) {
        Integer x = intConst(f.getStack(sp - 2));
        Integer y = intConst(f.getStack(sp - 1));
        if (x == null || y == null) {
          return null;
        }
        return switch (op) {
          case IF_ICMPEQ -> x.equals(y);
          case IF_ICMPNE -> !x.equals(y);
          case IF_ICMPLT -> x < y;
          case IF_ICMPGE -> x >= y;
          case IF_ICMPGT -> x > y;
          default -> x <= y;
        };
      }
      return null;
    }

    /** {@code x < bound}: a constant or field bound sanitises x; a loop counter compared with x makes x a loop bound. */
    private Frame<TV> bound(Frame<TV> f, TV x, TV other) {
      if (x.labels().isEmpty()) {
        return f;
      }
      if (other.local() >= 0 && counters.contains(other.local()) && other.labels().isEmpty()) {
        sink("SIZE", x.labels(), "loop bound");
        return f;
      }
      Frame<TV> r = new Frame<>(f);
      clear(r, x);
      return r;
    }

    private static void clear(Frame<TV> f, TV v) {
      if (v.local() >= 0) {
        f.setLocal(v.local(), f.getLocal(v.local()).with(Set.of()));
        for (int s = 0; s < f.getStackSize(); s++) {
          if (f.getStack(s).local() == v.local()) {
            f.setStack(s, f.getStack(s).with(Set.of()));
          }
        }
      }
    }

    /** {@code sb.append(x)} / {@code new File(x)}: the receiver object now carries x's labels. */
    private static void taintObject(Frame<TV> f, TV receiver, Set<String> labels) {
      for (int l = 0; l < f.getLocals(); l++) {
        TV v = f.getLocal(l);
        if (l == receiver.local() || (receiver.origin() != null && receiver.origin().startsWith("new@") && receiver.origin().equals(v.origin()))) {
          f.setLocal(l, v.with(union(v.labels(), labels)));
        }
      }
      for (int s = 0; s < f.getStackSize(); s++) {
        TV v = f.getStack(s);
        if ((receiver.local() >= 0 && v.local() == receiver.local())
          || (receiver.origin() != null && receiver.origin().startsWith("new@") && receiver.origin().equals(v.origin()))) {
          f.setStack(s, v.with(union(v.labels(), labels)));
        }
      }
    }

    private void flowAll(LabelNode dflt, List<LabelNode> labels, Frame<TV> out) throws AnalyzerException {
      flow(insns.indexOf(dflt), out);
      for (LabelNode l : labels) {
        flow(insns.indexOf(l), out);
      }
    }

    private void flow(int j, Frame<TV> f) throws AnalyzerException {
      if (j >= frames.length) {
        return;
      }
      boolean changed;
      if (frames[j] == null) {
        frames[j] = new Frame<>(f);
        changed = true;
      } else {
        changed = frames[j].merge(f, interp);
      }
      if (changed && !queued[j]) {
        queued[j] = true;
        work.push(j);
      }
    }
  }
}
