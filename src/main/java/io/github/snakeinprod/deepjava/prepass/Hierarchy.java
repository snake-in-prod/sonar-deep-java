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

/** Supertypes of project, library ({@code sonar.java.libraries} jars) and JDK classes. */
public final class Hierarchy {

  private final Map<String, ClassFacts> project;
  private final List<JarFile> libraries = new ArrayList<>();
  private final Map<String, List<String>> supers = new HashMap<>();

  public Hierarchy(Map<String, ClassFacts> project, List<Path> libraryJars) {
    this.project = project;
    for (Path jar : libraryJars) {
      try {
        // ponytail: jars stay open for the analysis lifetime; the scanner JVM exits afterwards
        libraries.add(new JarFile(jar.toFile()));
      } catch (IOException e) {
        // not a jar (e.g. a class directory): ignored, only jars are indexed
      }
    }
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
    String entry = name + ".class";
    for (JarFile jar : libraries) {
      JarEntry e = jar.getJarEntry(entry);
      if (e != null) {
        return jar.getInputStream(e);
      }
    }
    return ClassLoader.getSystemResourceAsStream(entry);
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
