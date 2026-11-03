package io.github.snakeinprod.deepjava.prepass;

import io.github.snakeinprod.deepjava.prepass.ClassFacts.CallSite;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.ExFacts;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.FieldAccess;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.FieldFacts;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.Info;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.LockEdge;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.LockFacts;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.LockedCall;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.MethodFacts;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.NullFacts;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.ThrowSite;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.TryFacts;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.UseFacts;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeAnnotationNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

/**
 * Computes {@link ClassFacts} from one class file with one branch-sensitive data flow per method (nullness, value
 * use, locks, exceptions) plus the taint flow of {@link TaintScanner}.
 */
public final class BytecodeScanner implements Opcodes {

  private static final Set<String> NULLABLE_ANNOTATIONS = Set.of("Nullable", "CheckForNull", "NullableDecl");

  /**
   * Prefix of a call key for a lookup whose content the author knows: the receiver is a static field (a constant
   * table), there is no key ({@code queue.peek()}) or the key is computed ({@code map.get(id)}): usually an
   * invariant. Only lookups with a constant key ({@code props.get("host")}) keep library models with guards.
   */
  public static final String KNOWN_LOOKUP = "~";

  private static final Set<String> NULL_TESTS = Set.of(
    "java/util/Objects#isNull", "java/util/Objects#nonNull", "java/util/Objects#requireNonNull",
    "java/util/Objects#requireNonNullElse", "java/util/Objects#requireNonNullElseGet", "java/util/Optional#ofNullable");

  private static final Set<String> LOCK_OWNERS = Set.of("java/util/concurrent/locks/Lock", "java/util/concurrent/locks/ReentrantLock",
    "java/util/concurrent/locks/ReentrantReadWriteLock$ReadLock", "java/util/concurrent/locks/ReentrantReadWriteLock$WriteLock",
    "java/util/concurrent/locks/StampedLock");

  private BytecodeScanner() {
  }

  public static ClassFacts scan(byte[] bytes, int maxSteps) {
    return scan(bytes, maxSteps, TaintModels.defaults());
  }

  public static ClassFacts scan(byte[] bytes, int maxSteps, TaintModels taintModels) {
    ClassNode cn = new ClassNode();
    new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES);
    boolean switchMap = cn.fields.stream().anyMatch(f -> f.name.startsWith("$SwitchMap$"));
    Map<String, MethodFacts> methods = new HashMap<>();
    for (MethodNode m : cn.methods) {
      methods.put(m.name + m.desc, analyze(cn, m, maxSteps, switchMap, taintModels));
    }
    Map<String, FieldFacts> fields = new HashMap<>();
    for (FieldNode f : cn.fields) {
      fields.put(f.name, new FieldFacts(f.access, f.desc, annotations(f.visibleAnnotations, f.invisibleAnnotations)));
    }
    return new ClassFacts(cn.name, cn.superName, List.copyOf(cn.interfaces), cn.sourceFile, cn.access,
      annotations(cn.visibleAnnotations, cn.invisibleAnnotations), fields, methods);
  }

  /** Key of a call target: {@code owner#name(desc)ret}, prefixed with "!" when dispatch is not virtual. */
  public static String callKey(boolean exact, String owner, String name, String desc) {
    return (exact ? "!" : "") + owner + "#" + name + desc;
  }

  /** Call key without prefixes, used for statistics. */
  public static String statKey(String callKey) {
    int i = 0;
    while (i < callKey.length() && (callKey.charAt(i) == '!' || callKey.charAt(i) == '~')) {
      i++;
    }
    return callKey.substring(i);
  }

  static MethodFacts analyze(ClassNode cn, MethodNode m, int maxSteps, boolean switchMap, TaintModels taintModels) {
    Info info = new Info(m.access, annotations(m.visibleAnnotations, m.invisibleAnnotations), paramNames(m), firstLine(m));
    boolean bodyless = m.instructions.size() == 0 || (m.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0;
    boolean returnsNormally = bodyless || hasReturn(m);
    if (bodyless) {
      NullFacts nulls = new NullFacts(hasNullableAnnotation(m), Map.of(), false, true, Map.of(), Set.of(), null, false, Set.of());
      return new MethodFacts(info, nulls, UseFacts.EMPTY, LockFacts.EMPTY, ExFacts.EMPTY, ClassFacts.TaintFacts.EMPTY);
    }
    try {
      Flow flow = new Flow(cn, m, maxSteps, switchMap);
      MethodFacts f = flow.run(info, hasNullableAnnotation(m), returnsNormally);
      return new MethodFacts(f.info(), f.nulls(), f.uses(), f.locks(), f.ex(), TaintScanner.analyze(cn, m, maxSteps, taintModels));
    } catch (AnalyzerException | RuntimeException e) {
      // ponytail: unsupported bytecode (JSR/RET, inconsistent stack) gives no summary instead of failing the analysis
      return MethodFacts.empty(info, returnsNormally);
    }
  }

  static Map<String, String> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
    Map<String, String> out = new HashMap<>();
    for (List<AnnotationNode> list : List.of(nn(visible), nn(invisible))) {
      for (AnnotationNode a : list) {
        String value = "";
        if (a.values != null) {
          for (int i = 0; i + 1 < a.values.size(); i += 2) {
            if ("value".equals(a.values.get(i))) {
              value = String.valueOf(a.values.get(i + 1));
            }
          }
        }
        out.put(simpleName(a.desc), value);
      }
    }
    return out;
  }

  private static String simpleName(String desc) {
    return desc.substring(Math.max(desc.lastIndexOf('/'), desc.lastIndexOf('$')) + 1, desc.length() - 1);
  }

  private static List<String> paramNames(MethodNode m) {
    if (m.localVariables == null) {
      return List.of();
    }
    int slot = (m.access & ACC_STATIC) == 0 ? 1 : 0;
    List<String> names = new ArrayList<>();
    for (Type t : Type.getArgumentTypes(m.desc)) {
      String name = "";
      for (LocalVariableNode lv : m.localVariables) {
        if (lv.index == slot) {
          name = lv.name;
          break;
        }
      }
      names.add(name);
      slot += t.getSize();
    }
    return names;
  }

  private static int firstLine(MethodNode m) {
    for (AbstractInsnNode insn : m.instructions) {
      if (insn instanceof LineNumberNode ln) {
        return ln.line;
      }
    }
    return -1;
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
        if (NULLABLE_ANNOTATIONS.contains(simpleName(a.desc))) {
          return true;
        }
      }
    }
    for (List<TypeAnnotationNode> list : List.of(nn(m.visibleTypeAnnotations), nn(m.invisibleTypeAnnotations))) {
      for (TypeAnnotationNode a : list) {
        if (new TypeReference(a.typeRef).getSort() == TypeReference.METHOD_RETURN && a.typePath == null
          && NULLABLE_ANNOTATIONS.contains(simpleName(a.desc))) {
          return true;
        }
      }
    }
    return false;
  }

  static <T> List<T> nn(List<T> l) {
    return l == null ? List.of() : l;
  }

  static boolean isRef(Type t) {
    return t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY;
  }

  /**
   * Abstract value. {@code nullLit}: can be the null constant; {@code calls}: call results it can come from (or,
   * in the extra slots, a set of names); {@code local}: local variable it was loaded from, or -1 (-2 static field,
   * -3 instance field, -4 string constant); {@code origin}: where it comes from ({@code field:o#n}, {@code static:o#n},
   * {@code class:T}, {@code new:T}, a constant {@code I:1}, {@code S:text}, {@code null}), or null.
   */
  record V(int size, boolean nullLit, Set<String> calls, int local, String origin) implements Value {
    static final V U1 = new V(1, false, Set.of(), -1, null);
    static final V U2 = new V(2, false, Set.of(), -1, null);
    static final V NULL = new V(1, true, Set.of(), -1, "null");

    static V names(Set<String> names) {
      return new V(1, false, Set.copyOf(names), -1, null);
    }

    @Override
    public int getSize() {
      return size;
    }

    boolean isNullConstant() {
      return nullLit && calls.isEmpty();
    }

    boolean isConstant() {
      return origin != null && (origin.startsWith("I:") || origin.startsWith("S:") || origin.equals("null"));
    }

    V withLocal(int l) {
      return new V(size, nullLit, calls, l, origin);
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

    private static V constant(int size, String origin, int local) {
      return new V(size, false, Set.of(), local, origin);
    }

    @Override
    public V newOperation(AbstractInsnNode insn) {
      int op = insn.getOpcode();
      switch (op) {
        case ACONST_NULL:
          return V.NULL;
        case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5:
          return constant(1, "I:" + (op - ICONST_0), -1);
        case BIPUSH, SIPUSH:
          return constant(1, "I:" + ((IntInsnNode) insn).operand, -1);
        case LCONST_0, LCONST_1, DCONST_0, DCONST_1:
          return V.U2;
        case LDC:
          Object c = ((LdcInsnNode) insn).cst;
          if (c instanceof Long || c instanceof Double) {
            return V.U2;
          }
          if (c instanceof String s) {
            return constant(1, "S:" + s, -4);
          }
          if (c instanceof Integer i) {
            return constant(1, "I:" + i, -1);
          }
          if (c instanceof Type t && t.getSort() == Type.OBJECT) {
            return constant(1, "class:" + t.getInternalName(), -1);
          }
          return V.U1;
        case GETSTATIC:
          FieldInsnNode f = (FieldInsnNode) insn;
          Type t = Type.getType(f.desc);
          return isRef(t) ? new V(1, false, Set.of(), -2, "static:" + f.owner + "#" + f.name) : newValue(t);
        case NEW:
          return new V(1, false, Set.of(), -1, "new:" + ((TypeInsnNode) insn).desc);
        default:
          return V.U1;
      }
    }

    @Override
    public V copyOperation(AbstractInsnNode insn, V v) {
      if (insn.getOpcode() == ALOAD) {
        return v.withLocal(((VarInsnNode) insn).var);
      }
      if (insn.getOpcode() == ASTORE && v.local() != -1) {
        return v.withLocal(-1);
      }
      return v;
    }

    @Override
    public V unaryOperation(AbstractInsnNode insn, V v) {
      switch (insn.getOpcode()) {
        case LNEG, DNEG, I2L, I2D, L2D, F2L, F2D, D2L:
          return V.U2;
        case GETFIELD:
          FieldInsnNode f = (FieldInsnNode) insn;
          Type t = Type.getType(f.desc);
          if (f.name.startsWith("this$")) {
            return new V(1, false, Set.of(), -3, "this:" + t.getInternalName());
          }
          return isRef(t) ? new V(1, false, Set.of(), -3, "field:" + f.owner + "#" + f.name) : newValue(t);
        case CHECKCAST:
          return v;
        case NEWARRAY, ANEWARRAY:
          return new V(1, false, Set.of(), -1, "new:[");
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
        if (isRef(ret)) {
          boolean exact = mi.getOpcode() == INVOKESTATIC || mi.getOpcode() == INVOKESPECIAL;
          String key = callKey(exact, mi.owner, mi.name, mi.desc);
          // no key (queue.peek()) or a computed key: an invariant of the caller ("not empty", "key present")
          boolean knownLookup = !exact && (values.get(0).local() == -2 || values.size() == 1
            || values.get(1).local() != -2 && values.get(1).local() != -4);
          String origin = null;
          if (!exact && ("readLock".equals(mi.name) || "writeLock".equals(mi.name)) && values.get(0).origin() != null
            && mi.owner.endsWith("ReentrantReadWriteLock")) {
            origin = values.get(0).origin() + "." + mi.name;
          }
          return new V(1, false, Set.of(knownLookup ? KNOWN_LOOKUP + key : key), -1, origin);
        }
        return newValue(ret);
      }
      if (insn instanceof InvokeDynamicInsnNode indy) {
        return newValue(Type.getReturnType(indy.desc));
      }
      return new V(1, false, Set.of(), -1, "new:[");
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
      String origin = v.origin() != null && v.origin().equals(w.origin()) ? v.origin() : null;
      return new V(v.size(), v.nullLit() || w.nullLit(), calls, v.local() == w.local() ? v.local() : -1, origin);
    }
  }

  /** Worklist data flow over one method; ASM's Frame does the per-instruction stack effects. */
  private static final class Flow {
    private final ClassNode cn;
    private final MethodNode m;
    private final int maxSteps;
    private final boolean switchMap;
    private final boolean initializer;
    private final InsnList insns;
    private final Interp interp = new Interp();
    private final Frame<V>[] frames;
    private final int[] lines;
    private final List<TryCatchBlockNode>[] handlers;
    private final Deque<Integer> work = new ArrayDeque<>();
    private final boolean[] queued;

    // nullness / returns
    private final Map<Integer, Set<String>> nullReturns = new HashMap<>();
    private final Map<String, Integer> returnedCalls = new HashMap<>();
    private boolean onlyNull = true;
    private boolean anyReturn;
    private final Set<String> returnConstants = new HashSet<>();
    private boolean nonConstantReturn;
    private boolean mayReturnMinusOne;
    private final Set<String> returnedNew = new HashSet<>();
    // uses
    private final Set<String> nullChecks = new HashSet<>();
    private final Set<Integer> notDerefAtReturn = new HashSet<>();
    private final Set<Integer> closedParams = new HashSet<>();
    private final Set<Integer> refParams = new HashSet<>();
    private final Set<Integer> reassigned = new HashSet<>();
    private final Set<String> fieldEscapes = new HashSet<>();
    private final Map<String, Set<String>> fieldCalls = new HashMap<>();
    private final Map<String, String> fieldInits = new HashMap<>();
    // locks
    private final Set<FieldAccess> accesses = new LinkedHashSet<>();
    private final Set<LockEdge> edges = new LinkedHashSet<>();
    private final Set<LockedCall> lockedCalls = new LinkedHashSet<>();
    private final Map<String, Integer> acquired = new HashMap<>();
    private final Set<String> heldAtReturn = new HashSet<>();
    private final Map<String, Integer> reacquired = new HashMap<>();

    /** Extra local slots: path condition, last call, held locks, parameters not yet dereferenced. */
    private final int flag;
    private final int lastCall;
    private final int locks;
    private final int notDeref;
    private final int firstParam;
    private int paramEnd;

    /** Labels reached by a jump, a switch or an exception handler. */
    private final Set<LabelNode> joins = new HashSet<>();

    @SuppressWarnings("unchecked")
    Flow(ClassNode cn, MethodNode m, int maxSteps, boolean switchMap) {
      this.cn = cn;
      this.m = m;
      this.maxSteps = maxSteps;
      this.switchMap = switchMap;
      this.initializer = m.name.equals("<init>") || m.name.equals("<clinit>");
      this.insns = m.instructions;
      this.flag = m.maxLocals;
      this.lastCall = m.maxLocals + 1;
      this.locks = m.maxLocals + 2;
      this.notDeref = m.maxLocals + 3;
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
        joins.add(tcb.handler);
        for (int i = insns.indexOf(tcb.start); i < insns.indexOf(tcb.end); i++) {
          if (handlers[i] == null) {
            handlers[i] = new ArrayList<>();
          }
          handlers[i].add(tcb);
        }
      }
      for (AbstractInsnNode insn : insns) {
        if (insn instanceof JumpInsnNode j) {
          joins.add(j.label);
        } else if (insn instanceof TableSwitchInsnNode ts) {
          joins.add(ts.dflt);
          joins.addAll(ts.labels);
        } else if (insn instanceof LookupSwitchInsnNode ls) {
          joins.add(ls.dflt);
          joins.addAll(ls.labels);
        }
      }
    }

    MethodFacts run(Info info, boolean annotated, boolean returnsNormally) throws AnalyzerException {
      Frame<V> init = new Frame<>(m.maxLocals + 4, m.maxStack);
      int local = 0;
      if ((m.access & ACC_STATIC) == 0) {
        init.setLocal(local++, new V(1, false, Set.of(), -1, "this"));
      }
      for (Type t : Type.getArgumentTypes(m.desc)) {
        if (isRef(t)) {
          refParams.add(local);
        }
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
      Set<String> held = new HashSet<>();
      if ((m.access & ACC_SYNCHRONIZED) != 0) {
        String self = (m.access & ACC_STATIC) != 0 ? "class:" + cn.name : "this:" + cn.name;
        held.add(self);
        acquired.put(self, firstLine(m));
      }
      init.setLocal(locks, V.names(held));
      Set<String> params = new HashSet<>();
      refParams.forEach(p -> params.add(String.valueOf(p)));
      init.setLocal(notDeref, V.names(params));

      Set<String> invoked = new HashSet<>();
      Map<String, Integer> callSites = new HashMap<>();
      Map<String, Integer> valueSites = new HashMap<>();
      Map<String, List<Integer>> ignored = new HashMap<>();
      Map<String, Integer> staticWrites = new HashMap<>();
      Set<String> fieldWrites = new HashSet<>();
      Set<String> enumLoads = new HashSet<>();
      Map<String, Integer> switchMapLoads = new HashMap<>();
      List<CallSite> calls = new ArrayList<>();
      boolean callsSuper = false;
      for (int i = 0; i < insns.size(); i++) {
        AbstractInsnNode insn = insns.get(i);
        if (insn instanceof VarInsnNode v && v.getOpcode() >= ISTORE && v.getOpcode() <= ASTORE) {
          reassigned.add(v.var);
        }
        if (insn instanceof MethodInsnNode mi) {
          invoked.add(mi.name);
          Type ret = Type.getReturnType(mi.desc);
          String key = statKey(callKey(false, mi.owner, mi.name, mi.desc));
          boolean discarded = isDiscarded(insn);
          if (isRef(ret) && !discarded) {
            callSites.merge(key, 1, Integer::sum);
          }
          if (ret != Type.VOID_TYPE && !mi.name.equals("<init>")) {
            if (discarded) {
              ignored.computeIfAbsent(key, k -> new ArrayList<>()).add(lines[i]);
            } else {
              valueSites.merge(key, 1, Integer::sum);
            }
          }
          if (mi.getOpcode() == INVOKESPECIAL && mi.owner.equals(cn.superName) && mi.name.equals(m.name) && mi.desc.equals(m.desc)) {
            callsSuper = true;
          }
          boolean exact = mi.getOpcode() == INVOKESTATIC || mi.getOpcode() == INVOKESPECIAL;
          calls.add(new CallSite(callKey(exact, mi.owner, mi.name, mi.desc), lines[i], caught(i), nextLine(insn), nextIsTrivial(insn)));
        } else if (insn instanceof FieldInsnNode f) {
          String key = f.owner + "#" + f.name;
          if (f.getOpcode() == PUTSTATIC) {
            staticWrites.putIfAbsent(key, lines[i]);
          }
          if ((f.getOpcode() == PUTSTATIC || f.getOpcode() == PUTFIELD) && !initializer) {
            fieldWrites.add(key);
          }
          if (f.getOpcode() == GETSTATIC && f.desc.equals("L" + f.owner + ";")) {
            if (switchMap && m.name.equals("<clinit>")) {
              switchMapLoads.putIfAbsent(key, lines[i]);
            } else if (!(f.owner.equals(cn.name) && (m.name.equals("<clinit>") || m.name.equals("$values")))) {
              enumLoads.add(key);
            }
          }
        }
      }

      flow(0, init);
      int steps = 0;
      while (!work.isEmpty()) {
        if (++steps > maxSteps) {
          return MethodFacts.empty(info, returnsNormally);
        }
        int i = work.pop();
        queued[i] = false;
        step(i, frames[i]);
      }

      Map<String, Integer> checks = new HashMap<>();
      for (String c : nullChecks) {
        checks.merge(c.substring(0, c.lastIndexOf('@')), 1, Integer::sum);
      }
      Set<Integer> derefParams = new HashSet<>(refParams);
      derefParams.removeAll(notDerefAtReturn);
      derefParams.removeAll(reassigned);
      if (!anyReturn && !returnsNormally) {
        derefParams.clear();
      }
      Set<Integer> derefShifted = new HashSet<>();
      derefParams.forEach(p -> derefShifted.add(paramIndex(p)));
      Set<Integer> closedShifted = new HashSet<>();
      closedParams.forEach(p -> closedShifted.add(paramIndex(p)));

      String constant = anyReturn && !nonConstantReturn && returnConstants.size() == 1 ? returnConstants.iterator().next() : null;
      NullFacts nulls = new NullFacts(annotated, Map.copyOf(nullReturns), anyReturn && onlyNull, returnsNormally,
        Map.copyOf(returnedCalls), Set.copyOf(invoked), constant, mayReturnMinusOne, Set.copyOf(returnedNew));
      UseFacts uses = new UseFacts(Map.copyOf(callSites), Map.copyOf(checks), Map.copyOf(valueSites), Map.copyOf(ignored),
        Set.copyOf(derefShifted), Set.copyOf(closedShifted), callsSuper, Map.copyOf(staticWrites), Set.copyOf(fieldWrites),
        Set.copyOf(fieldEscapes), Map.copyOf(fieldCalls), Map.copyOf(fieldInits), Set.copyOf(enumLoads), Map.copyOf(switchMapLoads));
      LockFacts lockFacts = new LockFacts(List.copyOf(accesses), List.copyOf(edges), List.copyOf(lockedCalls), Map.copyOf(acquired),
        Set.copyOf(heldAtReturn), Map.copyOf(reacquired));
      ExFacts ex = new ExFacts(List.copyOf(calls), List.copyOf(throwSites), tries());
      return new MethodFacts(info, nulls, uses, lockFacts, ex, ClassFacts.TaintFacts.EMPTY);
    }

    private final List<ThrowSite> throwSites = new ArrayList<>();
    private final Set<Integer> throwSeen = new HashSet<>();

    /** Local slot to parameter index (0-based, without {@code this}). */
    private int paramIndex(int slot) {
      int s = firstParam;
      int idx = 0;
      for (Type t : Type.getArgumentTypes(m.desc)) {
        if (s == slot) {
          return idx;
        }
        s += t.getSize();
        idx++;
      }
      return -1;
    }

    private Set<String> caught(int i) {
      if (handlers[i] == null) {
        return Set.of();
      }
      Set<String> types = new HashSet<>();
      for (TryCatchBlockNode tcb : handlers[i]) {
        types.add(tcb.type == null ? "*" : tcb.type);
      }
      return Set.copyOf(types);
    }

    private List<TryFacts> tries() {
      List<TryFacts> out = new ArrayList<>();
      for (TryCatchBlockNode tcb : m.tryCatchBlocks) {
        if (tcb.type == null) {
          continue;
        }
        Set<String> keys = new HashSet<>();
        boolean lambda = false;
        for (int i = insns.indexOf(tcb.start); i < insns.indexOf(tcb.end); i++) {
          AbstractInsnNode insn = insns.get(i);
          if (insn instanceof MethodInsnNode mi) {
            boolean exact = mi.getOpcode() == INVOKESTATIC || mi.getOpcode() == INVOKESPECIAL;
            keys.add(callKey(exact, mi.owner, mi.name, mi.desc));
          } else if (insn instanceof InvokeDynamicInsnNode) {
            lambda = true;
          } else if (insn.getOpcode() == NEW && ((TypeInsnNode) insn).desc.matches(".*\\$\\d+")) {
            lambda = true;
          }
        }
        out.add(new TryFacts(tcb.type, firstLineFrom(insns.indexOf(tcb.handler)), Set.copyOf(keys), lambda));
      }
      return out;
    }

    /** First line number at or after an instruction (a handler label carries the line of the code before it). */
    private int firstLineFrom(int index) {
      for (int i = index; i < insns.size(); i++) {
        if (insns.get(i) instanceof LineNumberNode ln) {
          return ln.line;
        }
      }
      return lines[index];
    }

    private static boolean isDiscarded(AbstractInsnNode call) {
      AbstractInsnNode next = real(call.getNext());
      return next != null && (next.getOpcode() == POP || next.getOpcode() == POP2);
    }

    private static AbstractInsnNode real(AbstractInsnNode insn) {
      while (insn != null && insn.getOpcode() < 0) {
        insn = insn.getNext();
      }
      return insn;
    }

    private int nextLine(AbstractInsnNode call) {
      AbstractInsnNode next = real(call.getNext());
      if (next != null && (next.getOpcode() == POP || next.getOpcode() == POP2)) {
        next = real(next.getNext());
      }
      return next == null ? -1 : lines[insns.indexOf(next)];
    }

    /** What follows the call is only a return, a throw or a jump (e.g. {@code fail(); return null;}). */
    private boolean nextIsTrivial(AbstractInsnNode call) {
      AbstractInsnNode insn = call.getNext();
      for (int n = 0; insn != null && n < 24; n++, insn = insn.getNext()) {
        if (insn instanceof LabelNode l && joins.contains(l)) {
          // a join point: what follows is also reached from elsewhere
          return true;
        }
        int op = insn.getOpcode();
        if (op < 0) {
          continue;
        }
        if ((op >= IRETURN && op <= RETURN) || op == ATHROW || op == GOTO) {
          return true;
        }
        AbstractInsnNode prev = insn.getPrevious();
        while (prev != null && prev.getOpcode() < 0) {
          prev = prev.getPrevious();
        }
        boolean constantStore = op >= ISTORE && op <= ASTORE && prev != null
          && (prev.getOpcode() >= ACONST_NULL && prev.getOpcode() <= LDC);
        // "fail(); x = null;" keeps javac's definite assignment happy: not real code
        boolean sideEffect = (insn instanceof MethodInsnNode mi && !mi.name.equals("<init>"))
          || (op >= ISTORE && op <= ASTORE && !constantStore) || op == PUTFIELD || op == PUTSTATIC || op == IINC || (op >= IASTORE && op <= SASTORE)
          || insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode;
        if (sideEffect) {
          return false;
        }
      }
      return true;
    }

    /** Type of an object created by {@code new} in this method, or null. */
    private static String createdType(V v) {
      String o = v.origin();
      if (o == null || o.equals("new:[")) {
        return null;
      }
      return o.startsWith("new:") ? o.substring(4) : o.startsWith("empty:") ? o.substring(6) : null;
    }

    private void recordNullCheck(V v, int insnIndex) {
      if (v != null) {
        for (String c : v.calls()) {
          nullChecks.add(statKey(c) + "@" + insnIndex);
        }
      }
    }

    private String lockId(V v, boolean instanceMethod) {
      if (v.local() == 0 && instanceMethod && "this".equals(v.origin())) {
        return "this:" + cn.name;
      }
      String o = v.origin();
      return o != null && (o.startsWith("field:") || o.startsWith("static:") || o.startsWith("class:") || o.startsWith("this:")) ? o : null;
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
      boolean instance = (m.access & ACC_STATIC) == 0;
      int sp = f.getStackSize();
      V top = sp > 0 ? f.getStack(sp - 1) : null;
      V second = sp > 1 ? f.getStack(sp - 2) : null;
      Set<String> held = f.getLocal(locks).calls();
      Frame<V> out = new Frame<>(f);

      if (op >= IRETURN && op <= RETURN) {
        anyReturn |= op != RETURN;
        notDerefAtReturn.addAll(f.getLocal(notDeref).calls().stream().map(Integer::valueOf).toList());
        for (String l : held) {
          if (l.startsWith("lock:")) {
            heldAtReturn.add(l);
          }
        }
        if (op != RETURN) {
          if (top.isConstant()) {
            returnConstants.add(top.origin());
          } else {
            nonConstantReturn = true;
          }
          if ("I:-1".equals(top.origin())) {
            mayReturnMinusOne = true;
          }
        }
      }
      if (op == ARETURN) {
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
          returnedCalls.putIfAbsent(c, lines[i]);
        }
        String created = createdType(top);
        if (created != null) {
          returnedNew.add(created);
        }
      }
      if (op == JSR || op == RET) {
        throw new AnalyzerException(insn, "JSR/RET not supported");
      }
      if (op == ATHROW && throwSeen.add(i)) {
        // "?": a rethrown variable of unknown type
        throwSites.add(new ThrowSite(createdType(top) != null ? createdType(top) : "?", lines[i], caught(i)));
      }

      // null tests feeding call-site statistics
      if (op == IFNULL || op == IFNONNULL) {
        recordNullCheck(top, i);
      } else if (op == IF_ACMPEQ || op == IF_ACMPNE) {
        recordNullCheck(top.isNullConstant() ? second : second.isNullConstant() ? top : null, i);
      }

      // dereferences of parameters, field use, locks
      Set<Integer> derefs = new HashSet<>();
      String newLock = null;
      String releasedLock = null;
      if (insn instanceof MethodInsnNode mi) {
        int argc = Type.getArgumentTypes(mi.desc).length;
        boolean hasReceiver = op != INVOKESTATIC;
        if (NULL_TESTS.contains(mi.owner + "#" + mi.name)) {
          for (int a = 0; a < argc; a++) {
            recordNullCheck(f.getStack(sp - 1 - a), i);
          }
          if (mi.name.startsWith("requireNonNull") && argc >= 1) {
            derefs.add(f.getStack(sp - argc).local());
          }
        }
        for (int a = 0; a < argc; a++) {
          String o = f.getStack(sp - 1 - a).origin();
          if (o != null && (o.startsWith("field:") || o.startsWith("static:"))) {
            fieldEscapes.add(o.substring(o.indexOf(':') + 1));
          }
        }
        if (hasReceiver && !mi.name.equals("<init>")) {
          V receiver = f.getStack(sp - 1 - argc);
          derefs.add(receiver.local());
          String o = receiver.origin();
          if (o != null && (o.startsWith("field:") || o.startsWith("static:"))) {
            fieldCalls.computeIfAbsent(o.substring(o.indexOf(':') + 1), k -> new HashSet<>()).add(mi.name + "@" + lines[i]);
          }
          if ("close".equals(mi.name) && argc == 0 && receiver.local() >= firstParam && receiver.local() < paramEnd) {
            closedParams.add(receiver.local());
          }
          if (LOCK_OWNERS.contains(mi.owner) && o != null) {
            boolean stamped = mi.owner.endsWith("StampedLock");
            String id = "lock:" + o.substring(o.indexOf(':') + 1);
            if (Set.of("lock", "lockInterruptibly").contains(mi.name) || stamped && Set.of("writeLock", "readLock").contains(mi.name)) {
              newLock = id;
              if (stamped && held.contains(id)) {
                reacquired.putIfAbsent(id, lines[i]);
              }
            } else if (mi.name.startsWith("unlock")) {
              releasedLock = id;
            }
          }
        }
        if (!held.isEmpty() && newLock == null && releasedLock == null) {
          boolean exact = op == INVOKESTATIC || op == INVOKESPECIAL;
          lockedCalls.add(new LockedCall(callKey(exact, mi.owner, mi.name, mi.desc), held, lines[i]));
        }
      } else if (insn instanceof InvokeDynamicInsnNode indy) {
        int argc = Type.getArgumentTypes(indy.desc).length;
        for (int a = 0; a < argc; a++) {
          String o = f.getStack(sp - 1 - a).origin();
          if (o != null && (o.startsWith("field:") || o.startsWith("static:"))) {
            fieldEscapes.add(o.substring(o.indexOf(':') + 1));
          }
        }
      } else if (insn instanceof FieldInsnNode fi) {
        String key = fi.owner + "#" + fi.name;
        boolean write = op == PUTFIELD || op == PUTSTATIC;
        if (op == GETFIELD) {
          derefs.add(top.local());
        } else if (op == PUTFIELD) {
          derefs.add(second.local());
        }
        if (write) {
          V value = top;
          String o = value.origin();
          if (o != null && (o.startsWith("field:") || o.startsWith("static:"))) {
            fieldEscapes.add(o.substring(o.indexOf(':') + 1));
          }
          if (initializer) {
            String c = value.isConstant() || (o != null && o.startsWith("empty:")) ? value.origin() : "?";
            fieldInits.merge(key, c, (a, b) -> a.equals(b) ? a : "?");
          }
        }
        if (!initializer) {
          accesses.add(new FieldAccess(key, write, lines[i], held));
        }
      } else if (op == ARRAYLENGTH || op == ATHROW) {
        derefs.add(top.local());
      } else if (op >= IALOAD && op <= SALOAD) {
        derefs.add(second.local());
      } else if (op >= IASTORE && op <= SASTORE) {
        derefs.add(f.getStack(sp - 3).local());
        if (op == AASTORE && top.origin() != null && (top.origin().startsWith("field:") || top.origin().startsWith("static:"))) {
          fieldEscapes.add(top.origin().substring(top.origin().indexOf(':') + 1));
        }
      } else if (op == ARETURN && top.origin() != null && (top.origin().startsWith("field:") || top.origin().startsWith("static:"))) {
        fieldEscapes.add(top.origin().substring(top.origin().indexOf(':') + 1));
      } else if (op == MONITORENTER) {
        derefs.add(top.local());
        newLock = lockId(top, instance);
      } else if (op == MONITOREXIT) {
        releasedLock = lockId(top, instance);
      }

      out.execute(insn, interp);
      if (op == ACONST_NULL && !f.getLocal(flag).nullLit()) {
        // a null created on a "parameter is null" path is the caller's null, not this method's
        out.setStack(out.getStackSize() - 1, V.U1);
      }
      if (insn instanceof MethodInsnNode mi) {
        boolean exact = op == INVOKESTATIC || op == INVOKESPECIAL;
        out.setLocal(lastCall, V.names(Set.of(callKey(exact, mi.owner, mi.name, mi.desc))));
        String o = op == INVOKESPECIAL && "<init>".equals(mi.name) && "()V".equals(mi.desc) ? f.getStack(sp - 1).origin() : null;
        if (o != null && o.startsWith("new:")) {
          // new HashMap<>(): an empty collection
          for (int s = 0; s < out.getStackSize(); s++) {
            if (o.equals(out.getStack(s).origin())) {
              V v = out.getStack(s);
              out.setStack(s, new V(v.size(), v.nullLit(), v.calls(), v.local(), "empty:" + o.substring(4)));
            }
          }
        }
      }
      Set<String> remaining = new HashSet<>(f.getLocal(notDeref).calls());
      if (derefs.stream().anyMatch(d -> remaining.remove(String.valueOf(d)))) {
        out.setLocal(notDeref, V.names(remaining));
      }
      if (newLock != null || releasedLock != null) {
        Set<String> nextHeld = new HashSet<>(held);
        if (newLock != null) {
          for (String h : held) {
            if (!h.equals(newLock)) {
              edges.add(new LockEdge(h, newLock, lines[i]));
            }
          }
          acquired.putIfAbsent(newLock, lines[i]);
          nextHeld.add(newLock);
        }
        if (releasedLock != null) {
          nextHeld.remove(releasedLock);
        }
        out.setLocal(locks, V.names(nextHeld));
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
     * A parameter or field known to be null marks the path as conditional instead (see {@link #flag}).
     */
    private Frame<V> refine(Frame<V> f, int k, boolean isNull) {
      if (k <= -2 && isNull) {
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
