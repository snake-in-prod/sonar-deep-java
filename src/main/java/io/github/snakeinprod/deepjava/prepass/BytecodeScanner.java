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

  /** Key of a call target: {@code owner#name(desc)ret}, prefixed with "!" when dispatch is not virtual. */
  public static String callKey(boolean exact, String owner, String name, String desc) {
    return (exact ? "!" : "") + owner + "#" + name + desc;
  }

  static MethodFacts analyze(MethodNode m, int maxSteps) {
    boolean annotated = hasNullableAnnotation(m);
    int sort = Type.getReturnType(m.desc).getSort();
    boolean returnsRef = sort == Type.OBJECT || sort == Type.ARRAY;
    if (!returnsRef || m.instructions.size() == 0 || (m.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) {
      return annotated ? new MethodFacts(true, -1, Map.of(), Set.of()) : MethodFacts.NONE;
    }
    try {
      return new Flow(m, maxSteps).run(annotated);
    } catch (AnalyzerException | RuntimeException e) {
      // ponytail: unsupported bytecode (JSR/RET, inconsistent stack) gives no summary instead of failing the analysis
      return annotated ? new MethodFacts(true, -1, Map.of(), Set.of()) : MethodFacts.NONE;
    }
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
          return c instanceof Long || c instanceof Double ? V.U2 : V.U1;
        case GETSTATIC:
          return newValue(Type.getType(((FieldInsnNode) insn).desc));
        default:
          return V.U1;
      }
    }

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
          return newValue(Type.getType(((FieldInsnNode) insn).desc));
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
          return new V(1, false, Set.of(callKey(exact, mi.owner, mi.name, mi.desc)), -1);
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

    private int nullReturnLine = -1;
    private final Map<String, Integer> nullableCalls = new HashMap<>();

    @SuppressWarnings("unchecked")
    Flow(MethodNode m, int maxSteps) {
      this.m = m;
      this.maxSteps = maxSteps;
      this.insns = m.instructions;
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

    MethodFacts run(boolean annotated) throws AnalyzerException {
      Frame<V> init = new Frame<>(m.maxLocals, m.maxStack);
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
      while (local < m.maxLocals) {
        init.setLocal(local++, V.U1);
      }
      flow(0, init);

      Set<String> invoked = new HashSet<>();
      for (AbstractInsnNode insn : insns) {
        if (insn instanceof MethodInsnNode mi) {
          invoked.add(mi.name);
        }
      }

      int steps = 0;
      while (!work.isEmpty()) {
        if (++steps > maxSteps) {
          return new MethodFacts(annotated, -1, Map.of(), Set.of());
        }
        int i = work.pop();
        queued[i] = false;
        step(i, frames[i]);
      }
      return new MethodFacts(annotated, nullReturnLine, Map.copyOf(nullableCalls), Set.copyOf(invoked));
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
          flow(insns.indexOf(tcb.handler), h);
        }
      }
      int sp = f.getStackSize();
      V top = sp > 0 ? f.getStack(sp - 1) : null;
      V second = sp > 1 ? f.getStack(sp - 2) : null;
      if (op == ARETURN) {
        if (top.nullLit() && nullReturnLine == -1) {
          nullReturnLine = lines[i];
        }
        for (String c : top.calls()) {
          nullableCalls.putIfAbsent(c, lines[i]);
        }
      }
      if (op == JSR || op == RET) {
        throw new AnalyzerException(insn, "JSR/RET not supported");
      }

      Frame<V> out = new Frame<>(f);
      out.execute(insn, interp);

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

    /** On a branch where local {@code k} is known null / non-null, overwrite it and its copies on the stack. */
    private static Frame<V> refine(Frame<V> f, int k, boolean isNull) {
      if (k < 0) {
        return f;
      }
      Frame<V> r = new Frame<>(f);
      V v = isNull ? V.NULL : V.U1;
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
