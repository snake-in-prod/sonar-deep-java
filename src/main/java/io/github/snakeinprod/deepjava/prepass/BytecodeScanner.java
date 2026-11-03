package io.github.snakeinprod.deepjava.prepass;

import io.github.snakeinprod.deepjava.prepass.ClassFacts.MethodFacts;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypeReference;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
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
import org.objectweb.asm.tree.TypeAnnotationNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

/**
 * Computes {@link ClassFacts} from one class file: for each method returning a reference, whether it can
 * return {@code null} directly and which call results can reach a {@code return}.
 * Branch-sensitive on {@code x == null} / {@code x != null}, so checked values are not reported.
 */
public final class BytecodeScanner implements Opcodes {

  private static final Set<String> NULLABLE_ANNOTATIONS = Set.of("Nullable", "CheckForNull", "NullableDecl");

  private BytecodeScanner() {
  }

  public static ClassFacts scan(byte[] bytes, int maxSteps) {
    ClassNode cn = new ClassNode();
    new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES);
    Map<String, MethodFacts> methods = new HashMap<>();
    for (MethodNode m : cn.methods) {
      methods.put(m.name + m.desc, analyze(m, maxSteps));
    }
    return new ClassFacts(cn.name, cn.superName, List.copyOf(cn.interfaces), cn.sourceFile, methods);
  }

  /**
   * Prefix of a call key for a lookup whose content the author knows: the receiver is a static field (a constant
   * table), there is no key ({@code queue.peek()}) or the key is computed ({@code map.get(id)}): usually an
   * invariant. Only lookups with a constant key ({@code props.get("host")}) keep library models with guards.
   */
  public static final String KNOWN_LOOKUP = "~";

  /** Key of a call target: {@code owner#name(desc)ret}, prefixed with "!" when dispatch is not virtual. */
  public static String callKey(boolean exact, String owner, String name, String desc) {
    return (exact ? "!" : "") + owner + "#" + name + desc;
  }

  /** Call key without prefixes, used for call-site statistics. */
  public static String statKey(String callKey) {
    int i = 0;
    while (i < callKey.length() && (callKey.charAt(i) == '!' || callKey.charAt(i) == '~')) {
      i++;
    }
    return callKey.substring(i);
  }

  private static final Set<String> NULL_TESTS = Set.of(
    "java/util/Objects#isNull", "java/util/Objects#nonNull", "java/util/Objects#requireNonNull",
    "java/util/Objects#requireNonNullElse", "java/util/Objects#requireNonNullElseGet", "java/util/Optional#ofNullable");

  static MethodFacts analyze(MethodNode m, int maxSteps) {
    boolean annotated = hasNullableAnnotation(m);
    boolean bodyless = m.instructions.size() == 0 || (m.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0;
    boolean returnsNormally = bodyless || hasReturn(m);
    if (bodyless) {
      return MethodFacts.empty(annotated, returnsNormally);
    }
    // every method with a body is analysed: void methods hold most of the call sites counted for statistics
    try {
      return new Flow(m, maxSteps).run(annotated, returnsNormally);
    } catch (AnalyzerException | RuntimeException e) {
      // ponytail: unsupported bytecode (JSR/RET, inconsistent stack) gives no summary instead of failing the analysis
      return MethodFacts.empty(annotated, returnsNormally);
    }
  }

  private static boolean hasReturn(MethodNode m) {
    for (AbstractInsnNode insn : m.instructions) {
      if (insn.getOpcode() >= IRETURN && insn.getOpcode() <= RETURN) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasNullableAnnotation(MethodNode m) {
    for (List<AnnotationNode> list : List.of(nn(m.visibleAnnotations), nn(m.invisibleAnnotations))) {
      for (AnnotationNode a : list) {
        if (isNullable(a.desc)) {
          return true;
        }
      }
    }
    for (List<TypeAnnotationNode> list : List.of(nn(m.visibleTypeAnnotations), nn(m.invisibleTypeAnnotations))) {
      for (TypeAnnotationNode a : list) {
        if (new TypeReference(a.typeRef).getSort() == TypeReference.METHOD_RETURN && a.typePath == null && isNullable(a.desc)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isNullable(String desc) {
    String simple = desc.substring(Math.max(desc.lastIndexOf('/'), desc.lastIndexOf('$')) + 1, desc.length() - 1);
    return NULLABLE_ANNOTATIONS.contains(simple);
  }

  private static <T> List<T> nn(List<T> l) {
    return l == null ? List.of() : l;
  }

  /**
   * Abstract value. {@code nullLit}: can be the null constant; {@code calls}: call results it can come from;
   * {@code local}: local variable it was loaded from (for refinement on null checks), or -1.
   */
  record V(int size, boolean nullLit, Set<String> calls, int local) implements Value {
    static final V U1 = new V(1, false, Set.of(), -1);
    static final V U2 = new V(2, false, Set.of(), -1);
    static final V NULL = new V(1, true, Set.of(), -1);

    @Override
    public int getSize() {
      return size;
    }

    boolean isNullConstant() {
      return nullLit && calls.isEmpty();
    }
  }

  static final class Interp extends Interpreter<V> {
    Interp() {
      super(ASM9);
    }

    @Override
    public V newValue(Type type) {
      if (type == Type.VOID_TYPE) {
        return null;
      }
      return type != null && type.getSize() == 2 ? V.U2 : V.U1;
    }

    @Override
    public V newOperation(AbstractInsnNode insn) {
      switch (insn.getOpcode()) {
        case ACONST_NULL:
          return V.NULL;
        case LCONST_0, LCONST_1, DCONST_0, DCONST_1:
          return V.U2;
        case LDC:
          Object c = ((LdcInsnNode) insn).cst;
          return c instanceof Long || c instanceof Double ? V.U2 : c instanceof String ? CONSTANT : V.U1;
        case GETSTATIC:
          Type t = Type.getType(((FieldInsnNode) insn).desc);
          return t.getSort() == Type.OBJECT ? STATIC_FIELD : newValue(t);
        default:
          return V.U1;
      }
    }

    /** A reference read from a static field; calls on it get the {@code ~} key prefix (constant receiver). */
    static final V STATIC_FIELD = new V(1, false, Set.of(), -2);
    /** A reference read from an instance field; a null check on it makes the path conditional. */
    static final V FIELD = new V(1, false, Set.of(), -3);
    /** A string constant. */
    static final V CONSTANT = new V(1, false, Set.of(), -4);

    @Override
    public V copyOperation(AbstractInsnNode insn, V v) {
      if (insn.getOpcode() == ALOAD) {
        return new V(1, v.nullLit(), v.calls(), ((VarInsnNode) insn).var);
      }
      if (insn.getOpcode() == ASTORE && v.local() != -1) {
        return new V(v.size(), v.nullLit(), v.calls(), -1);
      }
      return v;
    }

    @Override
    public V unaryOperation(AbstractInsnNode insn, V v) {
      switch (insn.getOpcode()) {
        case LNEG, DNEG, I2L, I2D, L2D, F2L, F2D, D2L:
          return V.U2;
        case GETFIELD:
          Type t = Type.getType(((FieldInsnNode) insn).desc);
          return t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY ? FIELD : newValue(t);
        case CHECKCAST:
          return v;
        case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE, TABLESWITCH, LOOKUPSWITCH, IRETURN, LRETURN, FRETURN, DRETURN, ARETURN,
          PUTSTATIC, ATHROW, MONITORENTER, MONITOREXIT, IFNULL, IFNONNULL:
          return null;
        default:
          return V.U1;
      }
    }

    @Override
    public V binaryOperation(AbstractInsnNode insn, V v1, V v2) {
      switch (insn.getOpcode()) {
        case LALOAD, DALOAD, LADD, LSUB, LMUL, LDIV, LREM, LSHL, LSHR, LUSHR, LAND, LOR, LXOR, DADD, DSUB, DMUL, DDIV, DREM:
          return V.U2;
        case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE, PUTFIELD:
          return null;
        default:
          return V.U1;
      }
    }

    @Override
    public V ternaryOperation(AbstractInsnNode insn, V v1, V v2, V v3) {
      return null;
    }

    @Override
    public V naryOperation(AbstractInsnNode insn, List<? extends V> values) {
      if (insn instanceof MethodInsnNode mi) {
        Type ret = Type.getReturnType(mi.desc);
        if (ret.getSort() == Type.OBJECT || ret.getSort() == Type.ARRAY) {
          boolean exact = mi.getOpcode() == INVOKESTATIC || mi.getOpcode() == INVOKESPECIAL;
          String key = callKey(exact, mi.owner, mi.name, mi.desc);
          // no key (queue.peek()) or a computed key: an invariant of the caller ("not empty", "key present")
          boolean knownLookup = !exact && (values.get(0).local() == -2 || values.size() == 1
            || values.get(1).local() != -2 && values.get(1).local() != -4);
          return new V(1, false, Set.of(knownLookup ? KNOWN_LOOKUP + key : key), -1);
        }
        return newValue(ret);
      }
      if (insn instanceof InvokeDynamicInsnNode indy) {
        return newValue(Type.getReturnType(indy.desc));
      }
      return V.U1;
    }

    @Override
    public void returnOperation(AbstractInsnNode insn, V value, V expected) {
      // facts are collected by Flow before the return executes
    }

    @Override
    public V merge(V v, V w) {
      if (v.equals(w)) {
        return v;
      }
      if (v.size() != w.size()) {
        return V.U1;
      }
      Set<String> calls = v.calls();
      if (!w.calls().isEmpty() && !v.calls().containsAll(w.calls())) {
        Set<String> u = new HashSet<>(v.calls());
        u.addAll(w.calls());
        calls = Set.copyOf(u);
      }
      return new V(v.size(), v.nullLit() || w.nullLit(), calls, v.local() == w.local() ? v.local() : -1);
    }
  }

  /** Worklist data flow over one method; ASM's Frame does the per-instruction stack effects. */
  private static final class Flow {
    private final MethodNode m;
    private final int maxSteps;
    private final InsnList insns;
    private final Interp interp = new Interp();
    private final Frame<V>[] frames;
    private final int[] lines;
    private final List<TryCatchBlockNode>[] handlers;
    private final Deque<Integer> work = new ArrayDeque<>();
    private final boolean[] queued;

    private final Map<Integer, Set<String>> nullReturns = new HashMap<>();
    private final Map<String, Integer> nullableCalls = new HashMap<>();
    private boolean onlyNull = true;
    private boolean anyReturn;
    /** {@code statKey@instructionIndex} of each null test of a call result. */
    private final Set<String> nullChecks = new HashSet<>();

    /**
     * Extra local slot holding the path condition: {@link V#NULL} on ordinary paths, {@link V#U1} on paths that
     * only exist when a parameter or field is null or an impossible exception was caught. Merging is "ordinary wins".
     */
    private final int flag;
    /**
     * Extra local slot holding the last call made on the path ({@code calls}), or {@link V#NULL} when some path
     * has made no call yet.
     */
    private final int lastCall;
    private final int firstParam;
    private int paramEnd;

    @SuppressWarnings("unchecked")
    Flow(MethodNode m, int maxSteps) {
      this.m = m;
      this.maxSteps = maxSteps;
      this.insns = m.instructions;
      this.flag = m.maxLocals;
      this.lastCall = m.maxLocals + 1;
      this.firstParam = (m.access & ACC_STATIC) == 0 ? 1 : 0;
      int n = insns.size();
      frames = new Frame[n];
      queued = new boolean[n];
      lines = new int[n];
      handlers = new List[n];
      int line = -1;
      for (int i = 0; i < n; i++) {
        if (insns.get(i) instanceof LineNumberNode ln) {
          line = ln.line;
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

    MethodFacts run(boolean annotated, boolean returnsNormally) throws AnalyzerException {
      Frame<V> init = new Frame<>(m.maxLocals + 2, m.maxStack);
      int local = 0;
      if ((m.access & ACC_STATIC) == 0) {
        init.setLocal(local++, V.U1);
      }
      for (Type t : Type.getArgumentTypes(m.desc)) {
        init.setLocal(local++, interp.newValue(t));
        if (t.getSize() == 2) {
          init.setLocal(local++, V.U1);
        }
      }
      paramEnd = local;
      while (local < m.maxLocals) {
        init.setLocal(local++, V.U1);
      }
      init.setLocal(flag, V.NULL);
      init.setLocal(lastCall, V.NULL);
      flow(0, init);

      Set<String> invoked = new HashSet<>();
      Map<String, Integer> callSites = new HashMap<>();
      for (AbstractInsnNode insn : insns) {
        if (insn instanceof MethodInsnNode mi) {
          invoked.add(mi.name);
          int sort = Type.getReturnType(mi.desc).getSort();
          if ((sort == Type.OBJECT || sort == Type.ARRAY) && !isDiscarded(insn)) {
            callSites.merge(statKey(callKey(false, mi.owner, mi.name, mi.desc)), 1, Integer::sum);
          }
        }
      }

      int steps = 0;
      while (!work.isEmpty()) {
        if (++steps > maxSteps) {
          return MethodFacts.empty(annotated, returnsNormally);
        }
        int i = work.pop();
        queued[i] = false;
        step(i, frames[i]);
      }
      Map<String, Integer> checks = new HashMap<>();
      for (String c : nullChecks) {
        checks.merge(c.substring(0, c.lastIndexOf('@')), 1, Integer::sum);
      }
      return new MethodFacts(annotated, Map.copyOf(nullReturns), anyReturn && onlyNull, returnsNormally,
        Map.copyOf(nullableCalls), Set.copyOf(invoked), Map.copyOf(callSites), Map.copyOf(checks));
    }

    private static boolean isDiscarded(AbstractInsnNode call) {
      AbstractInsnNode next = call.getNext();
      while (next != null && next.getOpcode() < 0) {
        next = next.getNext();
      }
      return next != null && next.getOpcode() == POP;
    }

    private void recordNullCheck(V v, int insnIndex) {
      if (v != null) {
        for (String c : v.calls()) {
          nullChecks.add(statKey(c) + "@" + insnIndex);
        }
      }
    }

    private void step(int i, Frame<V> f) throws AnalyzerException {
      AbstractInsnNode insn = insns.get(i);
      int op = insn.getOpcode();
      if (op < 0) {
        flow(i + 1, f);
        return;
      }
      if (handlers[i] != null) {
        for (TryCatchBlockNode tcb : handlers[i]) {
          Frame<V> h = new Frame<>(f);
          h.clearStack();
          h.push(V.U1);
          if ("java/lang/CloneNotSupportedException".equals(tcb.type)) {
            h.setLocal(flag, V.U1);
          }
          flow(insns.indexOf(tcb.handler), h);
        }
      }
      int sp = f.getStackSize();
      V top = sp > 0 ? f.getStack(sp - 1) : null;
      V second = sp > 1 ? f.getStack(sp - 2) : null;
      if (op == ARETURN) {
        anyReturn = true;
        onlyNull &= top.isNullConstant();
        // ponytail: "returns null only if an argument is null" is dropped; report it when the argument can be null
        if (top.nullLit() && f.getLocal(flag).nullLit()) {
          V last = f.getLocal(lastCall);
          Set<String> after = last.nullLit() ? Set.of() : last.calls();
          nullReturns.merge(lines[i], after, (a, b) -> {
            if (a.isEmpty() || b.isEmpty()) {
              return Set.of();
            }
            Set<String> u = new HashSet<>(a);
            u.addAll(b);
            return Set.copyOf(u);
          });
        }
        for (String c : top.calls()) {
          nullableCalls.putIfAbsent(c, lines[i]);
        }
      }
      if (op == JSR || op == RET) {
        throw new AnalyzerException(insn, "JSR/RET not supported");
      }
      if (op == IFNULL || op == IFNONNULL) {
        recordNullCheck(top, i);
      } else if (op == IF_ACMPEQ || op == IF_ACMPNE) {
        recordNullCheck(top.isNullConstant() ? second : second.isNullConstant() ? top : null, i);
      } else if (insn instanceof MethodInsnNode mi && NULL_TESTS.contains(mi.owner + "#" + mi.name)) {
        for (int a = 0; a < Type.getArgumentTypes(mi.desc).length; a++) {
          recordNullCheck(f.getStack(sp - 1 - a), i);
        }
      }

      Frame<V> out = new Frame<>(f);
      out.execute(insn, interp);
      if (op == ACONST_NULL && !f.getLocal(flag).nullLit()) {
        // a null created on a "parameter is null" path is the caller's null, not this method's
        out.setStack(out.getStackSize() - 1, V.U1);
      }
      if (insn instanceof MethodInsnNode mi) {
        boolean exact = op == INVOKESTATIC || op == INVOKESPECIAL;
        out.setLocal(lastCall, new V(1, false, Set.of(callKey(exact, mi.owner, mi.name, mi.desc)), -1));
      }

      if (insn instanceof JumpInsnNode j) {
        int target = insns.indexOf(j.label);
        int checked = -1;
        boolean nullOnJump = false;
        if (op == IFNULL || op == IFNONNULL) {
          checked = top.local();
          nullOnJump = op == IFNULL;
        } else if (op == IF_ACMPEQ || op == IF_ACMPNE) {
          V other = top.isNullConstant() ? second : second.isNullConstant() ? top : null;
          checked = other == null ? -1 : other.local();
          nullOnJump = op == IF_ACMPEQ;
        }
        flow(target, refine(out, checked, nullOnJump));
        if (op != GOTO) {
          flow(i + 1, refine(out, checked, !nullOnJump));
        }
      } else if (insn instanceof TableSwitchInsnNode ts) {
        flowAll(ts.dflt, ts.labels, out);
      } else if (insn instanceof LookupSwitchInsnNode ls) {
        flowAll(ls.dflt, ls.labels, out);
      } else if (!(op >= IRETURN && op <= RETURN) && op != ATHROW) {
        flow(i + 1, out);
      }
    }

    private void flowAll(LabelNode dflt, List<LabelNode> labels, Frame<V> out) throws AnalyzerException {
      flow(insns.indexOf(dflt), out);
      for (LabelNode l : labels) {
        flow(insns.indexOf(l), out);
      }
    }

    /**
     * On a branch where local {@code k} is known null / non-null, overwrite it and its copies on the stack.
     * A parameter known to be null marks the path as conditional instead (see {@link #flag}).
     */
    private Frame<V> refine(Frame<V> f, int k, boolean isNull) {
      if (k <= -2 && isNull) {
        // a field (static or instance) is null on this branch: null results here depend on object state
        Frame<V> r = new Frame<>(f);
        r.setLocal(flag, V.U1);
        return r;
      }
      if (k < 0) {
        return f;
      }
      Frame<V> r = new Frame<>(f);
      boolean param = k >= firstParam && k < paramEnd;
      V v = isNull && !param ? V.NULL : V.U1;
      if (isNull && param) {
        r.setLocal(flag, V.U1);
      }
      r.setLocal(k, v);
      for (int s = 0; s < r.getStackSize(); s++) {
        if (r.getStack(s).local() == k) {
          r.setStack(s, v);
        }
      }
      return r;
    }

    private void flow(int j, Frame<V> f) throws AnalyzerException {
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
