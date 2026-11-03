package io.github.snakeinprod.deepjava;

import io.github.snakeinprod.deepjava.prepass.BytecodeScanner;
import io.github.snakeinprod.deepjava.prepass.ClassFacts;
import io.github.snakeinprod.deepjava.prepass.Hierarchy;
import io.github.snakeinprod.deepjava.prepass.LibraryModels;
import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Compiles test sources and runs the pre-pass on them, without the cache. */
public final class TestSummaries {

  private TestSummaries() {
  }

  public static Summaries compile(Path out, String... sources) throws Exception {
    return compile(new Summaries.Thresholds(8, 0, 0), out, sources);
  }

  public static Summaries compile(Summaries.Thresholds thresholds, Path out, String... sources) throws Exception {
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    List<String> args = new ArrayList<>(List.of("-g", "-d", out.toString()));
    args.addAll(List.of(sources));
    if (javac.run(null, null, null, args.toArray(String[]::new)) != 0) {
      throw new IllegalStateException("compilation failed");
    }
    List<ClassFacts> facts = new ArrayList<>();
    try (Stream<Path> s = Files.walk(out)) {
      for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".class"))::iterator) {
        facts.add(BytecodeScanner.scan(Files.readAllBytes(p), 20000));
      }
    }
    return new Summaries(facts, new Hierarchy(Summaries.byName(facts), List.of()), LibraryModels.load(List.of()), thresholds);
  }
}
