package io.github.snakeinprod.deepjava.prepass;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;

/**
 * Supertypes of project, library ({@code sonar.java.libraries} jars and class directories) and JDK classes, and the
 * nullness annotations of library members.
 */
public final class Hierarchy {

  private final Map<String, ClassFacts> project;
  private final List<JarFile> libraries = new ArrayList<>();
  private final List<Path> libraryDirs = new ArrayList<>();
  private final Map<String, List<String>> supers = new HashMap<>();
  private final Map<String, Library> libraryClasses = new HashMap<>();

  /**
   * Annotations of a library class read from its bytecode (simple and qualified names, as {@link ClassFacts}).
   *
   * @param methods       per name + descriptor, annotations of the method and of its return type
   * @param params        per name + descriptor, annotations of each parameter
   * @param fields        per field name
   * @param getterFields  per name + descriptor, the field a plain getter returns ({@code return this.f;})
   */
  public record Library(Map<String, String> annotations, Map<String, Map<String, String>> methods,
    Map<String, List<Map<String, String>>> params, Map<String, Map<String, String>> fields, Map<String, String> getterFields) {
    static final Library NONE = new Library(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
  }

  public Hierarchy(Map<String, ClassFacts> project, List<Path> libraryPaths) {
    this.project = project;
    for (Path lib : libraryPaths) {
      if (java.nio.file.Files.isDirectory(lib)) {
        libraryDirs.add(lib);
        continue;
      }
      try {
        // ponytail: jars stay open for the analysis lifetime; the scanner JVM exits afterwards
        libraries.add(new JarFile(lib.toFile()));
      } catch (IOException e) {
        // neither a jar nor a directory: ignored
      }
    }
  }

  /** A library class (not a project or JDK class), or {@link Library#NONE}. */
  public synchronized Library library(String name) {
    return libraryClasses.computeIfAbsent(name, this::readLibrary);
  }

  private Library readLibrary(String name) {
    if (project.containsKey(name)) {
      return Library.NONE;
    }
    try (InputStream in = openLibrary(name)) {
      if (in == null) {
        return Library.NONE;
      }
      org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
      new ClassReader(in).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
      Map<String, Map<String, String>> methods = new HashMap<>();
      Map<String, List<Map<String, String>>> params = new HashMap<>();
      Map<String, String> getters = new HashMap<>();
      for (org.objectweb.asm.tree.MethodNode m : cn.methods) {
        Map<String, String> a = new HashMap<>(BytecodeScanner.annotations(m.visibleAnnotations, m.invisibleAnnotations));
        a.putAll(BytecodeScanner.returnTypeAnnotations(m));
        methods.put(m.name + m.desc, a);
        int argc = org.objectweb.asm.Type.getArgumentTypes(m.desc).length;
        List<Map<String, String>> p = new ArrayList<>();
        for (int i = 0; i < argc; i++) {
          p.add(BytecodeScanner.annotations(
            m.visibleParameterAnnotations == null || i >= m.visibleParameterAnnotations.length ? null : m.visibleParameterAnnotations[i],
            m.invisibleParameterAnnotations == null || i >= m.invisibleParameterAnnotations.length ? null : m.invisibleParameterAnnotations[i]));
        }
        params.put(m.name + m.desc, p);
        String field = BytecodeScanner.plainGetterField(m);
        if (field != null) {
          getters.put(m.name + m.desc, field);
        }
      }
      Map<String, Map<String, String>> fields = new HashMap<>();
      for (org.objectweb.asm.tree.FieldNode f : cn.fields) {
        fields.put(f.name, BytecodeScanner.annotations(f.visibleAnnotations, f.invisibleAnnotations));
      }
      return new Library(BytecodeScanner.annotations(cn.visibleAnnotations, cn.invisibleAnnotations), methods, params, fields, getters);
    } catch (IOException | RuntimeException e) {
      return Library.NONE;
    }
  }

  private InputStream openLibrary(String name) throws IOException {
    String entry = name + ".class";
    for (JarFile jar : libraries) {
      JarEntry e = jar.getJarEntry(entry);
      if (e != null) {
        return jar.getInputStream(e);
      }
    }
    for (Path dir : libraryDirs) {
      Path f = dir.resolve(entry);
      if (java.nio.file.Files.isRegularFile(f)) {
        return java.nio.file.Files.newInputStream(f);
      }
    }
    return null;
  }

  public List<String> directSupertypes(String name) {
    return supers.computeIfAbsent(name, this::load);
  }

  /** Breadth-first: the type itself, then its superclasses and interfaces. */
  public Set<String> selfAndSupertypes(String name) {
    Set<String> seen = new LinkedHashSet<>();
    Deque<String> todo = new ArrayDeque<>(List.of(name));
    while (!todo.isEmpty()) {
      String t = todo.poll();
      if (seen.add(t)) {
        todo.addAll(directSupertypes(t));
      }
    }
    return seen;
  }

  private List<String> load(String name) {
    ClassFacts c = project.get(name);
    if (c != null) {
      return supertypes(c.superName(), c.interfaces().toArray(String[]::new));
    }
    try (InputStream in = open(name)) {
      if (in == null) {
        return List.of();
      }
      ClassReader r = new ClassReader(in);
      return supertypes(r.getSuperName(), r.getInterfaces());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private InputStream open(String name) throws IOException {
    InputStream in = openLibrary(name);
    return in != null ? in : ClassLoader.getSystemResourceAsStream(name + ".class");
  }

  private static List<String> supertypes(String superName, String[] interfaces) {
    List<String> l = new ArrayList<>();
    if (superName != null) {
      l.add(superName);
    }
    l.addAll(List.of(interfaces));
    return l;
  }
}
