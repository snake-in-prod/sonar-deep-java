package io.github.snakeinprod.deepjava.prepass;

import io.github.snakeinprod.deepjava.DeepJavaPlugin;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.config.Configuration;

/**
 * The pre-pass, shared by all modules of one analysis. It reads the bytecode of the whole project once
 * (lazily, on first use by a rule): the standard Maven/Gradle output directories found under the project base
 * directory plus the {@code sonar.java.binaries} of every module seen so far. Per-class results are cached by
 * class file hash between analyses.
 */
@org.sonar.api.scanner.ScannerSide
public class ProjectSummaries implements Supplier<Summaries> {

  private static final Logger LOG = LoggerFactory.getLogger(ProjectSummaries.class);
  private static final int CACHE_FORMAT = 16;
  private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "src", ".scannerwork", ".gradle", ".idea");

  private final Configuration config;
  private final Path baseDir;
  private final Set<Path> binaries = new LinkedHashSet<>();
  private final Set<Path> libraries = new LinkedHashSet<>();
  private final Set<Path> testBinaries = new LinkedHashSet<>();
  private final Map<String, ClassFacts> scanned = new HashMap<>();
  private boolean discovered;
  private boolean stale = true;
  private boolean extend;
  private boolean announce;
  private String key;
  private Summaries summaries;

  /**
   * The last pre-pass of this JVM. The scanner analyses the modules of a project one after another in one JVM, and
   * every module sees the same compiled classes: the whole-program result is computed once and reused. One entry only,
   * so a JVM that scans many projects does not keep them all.
   */
  private record Memo(String key, Summaries summaries) {
  }

  private static Memo memo;

  /** Full computations so far (tests check that modules reuse one). */
  static int computations;

  public ProjectSummaries(Configuration config) {
    this.config = config;
    this.baseDir = config.get("sonar.projectBaseDir").map(Path::of).orElse(Path.of("")).toAbsolutePath();
  }

  /** Called for each module with its own settings, before its files are analysed. */
  public synchronized void addModule(Path moduleBaseDir, Configuration moduleConfig) {
    try {
      // new compiled classes change everything; new libraries and test classes only extend the result
      stale |= binaries.addAll(resolve(moduleBaseDir, moduleConfig.getStringArray("sonar.java.binaries")));
      extend |= libraries.addAll(resolve(moduleBaseDir, moduleConfig.getStringArray("sonar.java.libraries")));
      extend |= testBinaries.addAll(resolve(moduleBaseDir, moduleConfig.getStringArray("sonar.java.test.binaries")));
      if (!cacheEnabled()) {
        stale |= extend;
      }
      announce = true;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private boolean cacheEnabled() {
    return config.getBoolean(DeepJavaPlugin.PREPASS_CACHE).orElse(true);
  }

  private boolean parity() {
    return config.getBoolean(DeepJavaPlugin.REFERENCE_PARITY).orElse(false);
  }

  @Override
  public synchronized Summaries get() {
    try {
      if (stale) {
        long start = System.currentTimeMillis();
        discover();
        key = cacheEnabled() ? key() : null;
        synchronized (ProjectSummaries.class) {
          if (key != null && memo != null && memo.key().equals(key)) {
            summaries = memo.summaries();
            LOG.info("Deep Java pre-pass: reused (key {}), {} classes, {} ms", key, summaries.classes().size(),
              System.currentTimeMillis() - start);
          } else {
            summaries = build(key);
            memo = key == null ? null : new Memo(key, summaries);
          }
        }
        stale = false;
        extend = true;
      } else if (announce) {
        LOG.info("Deep Java pre-pass: reused (key {}), {} classes", key == null ? "cache off" : key, summaries.classes().size());
      }
      announce = false;
      if (extend) {
        extend(summaries);
        extend = false;
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return summaries;
  }

  /** Every module up front, so call-site statistics count the whole program from the first module on. */
  private void discover() throws IOException {
    if (!discovered) {
      binaries.addAll(discoverOutputDirs(baseDir));
      declaredModules(baseDir, "");
      if (parity()) {
        testBinaries.addAll(discoverTestDirs(baseDir));
      }
      discovered = true;
    }
  }

  /** Adds the libraries and (parity mode) test classes that modules brought since the result was computed. */
  private void extend(Summaries s) throws IOException {
    long start = System.currentTimeMillis();
    int libs = s.hierarchy().addLibraries(libraries);
    if (libs > 0) {
      s.forgetLibraryAnswers();
    }
    int tests = 0;
    if (parity()) {
      int maxSteps = config.getInt(DeepJavaPlugin.MAX_STEPS).orElse(20000);
      TaintModels taintModels = TaintModels.load(resolve(baseDir, config.getStringArray(DeepJavaPlugin.MODELS)));
      String version = CACHE_FORMAT + ":" + maxSteps + ":" + taintModels.digest();
      for (Path dir : testBinaries) {
        if (s.hasStats(dir)) {
          continue;
        }
        List<ClassFacts> facts = new ArrayList<>();
        forEachClass(List.of(dir), bytes -> {
          String key = sha256(bytes) + ":" + version;
          ClassFacts f = scanned.get(key);
          f = f != null ? f : BytecodeScanner.scan(bytes, maxSteps, taintModels);
          scanned.put(key, f);
          facts.add(f);
        });
        facts.removeIf(f -> s.classes().containsKey(f.name()));
        if (s.addStats(dir, facts)) {
          tests++;
        }
      }
    }
    if (libs > 0 || tests > 0) {
      LOG.info("Deep Java pre-pass: extended with {} libraries and {} test class directories in {} ms", libs, tests,
        System.currentTimeMillis() - start);
    }
  }

  /**
   * Identifies a pre-pass result: the settings it depends on and the compiled classes (path, count, total size and
   * latest modification time of the class files of each directory or jar).
   */
  private String key() throws IOException {
    StringBuilder sb = new StringBuilder(CACHE_FORMAT + "|");
    for (String k : List.of(DeepJavaPlugin.MAX_STEPS, DeepJavaPlugin.MAX_TARGETS, DeepJavaPlugin.MIN_SITES, DeepJavaPlugin.MIN_RATIO,
      DeepJavaPlugin.NULLABLE_ANNOTATIONS, DeepJavaPlugin.NULLABLE_IGNORED, DeepJavaPlugin.DATA_SOURCES, DeepJavaPlugin.LOG_PARAMETERS_SAFE,
      DeepJavaPlugin.REFERENCE_PARITY, DeepJavaPlugin.MODELS)) {
      sb.append(k).append('=').append(String.join(",", config.getStringArray(k))).append('|');
    }
    List<Path> sorted = new ArrayList<>(binaries);
    sorted.sort(null);
    for (Path p : sorted) {
      long[] stat = {0, 0, 0};
      if (Files.isDirectory(p)) {
        try (Stream<Path> files = Files.walk(p)) {
          for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".class"))::iterator) {
            stat[0]++;
            stat[1] += Files.size(f);
            stat[2] = Math.max(stat[2], Files.getLastModifiedTime(f).toMillis());
          }
        }
      } else if (Files.exists(p)) {
        stat[0] = 1;
        stat[1] = Files.size(p);
        stat[2] = Files.getLastModifiedTime(p).toMillis();
      }
      sb.append(p).append(':').append(stat[0]).append(':').append(stat[1]).append(':').append(stat[2]).append('|');
    }
    return sha256(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 16);
  }

  private Summaries build(String key) throws IOException {
    computations++;
    long start = System.currentTimeMillis();
    int maxSteps = config.getInt(DeepJavaPlugin.MAX_STEPS).orElse(20000);
    Summaries.Thresholds thresholds = new Summaries.Thresholds(
      config.getInt(DeepJavaPlugin.MAX_TARGETS).orElse(8),
      config.getInt(DeepJavaPlugin.MIN_SITES).orElse(5),
      config.getDouble(DeepJavaPlugin.MIN_RATIO).orElse(0.8));
    List<Path> models = resolve(baseDir, config.getStringArray(DeepJavaPlugin.MODELS));
    TaintModels taintModels = TaintModels.load(models);
    String version = CACHE_FORMAT + ":" + maxSteps + ":" + taintModels.digest();

    Path cacheFile = cacheFile();
    Map<String, ClassFacts> cache = scanned.isEmpty() ? readCache(cacheFile) : scanned;
    Map<String, ClassFacts> used = new HashMap<>();
    Map<String, ClassFacts> byName = new HashMap<>();
    int[] hits = {0};
    forEachClass(binaries, bytes -> {
      String classKey = sha256(bytes) + ":" + version;
      ClassFacts f = cache.get(classKey);
      if (f != null) {
        hits[0]++;
      } else {
        f = BytecodeScanner.scan(bytes, maxSteps, taintModels);
      }
      used.put(classKey, f);
      byName.putIfAbsent(f.name(), f);
    });
    long loaded = System.currentTimeMillis();
    scanned.putAll(used);
    writeCache(cacheFile, used);

    // test classes (parity mode) are added by extend(), like those of later modules
    Summaries s = new Summaries(byName.values(), List.of(), new Hierarchy(byName, List.copyOf(libraries)), LibraryModels.load(models), thresholds,
      new Summaries.Options(new Summaries.Nullable(config.getStringArray(DeepJavaPlugin.NULLABLE_ANNOTATIONS).length == 0
        ? Summaries.Nullable.DEFAULT.annotations() : Set.of(config.getStringArray(DeepJavaPlugin.NULLABLE_ANNOTATIONS)),
        Set.of(config.getStringArray(DeepJavaPlugin.NULLABLE_IGNORED))),
        config.getBoolean(DeepJavaPlugin.DATA_SOURCES).orElse(true), config.getBoolean(DeepJavaPlugin.LOG_PARAMETERS_SAFE).orElse(true),
        config.getBoolean(DeepJavaPlugin.REFERENCE_PARITY).orElse(false)));
    LOG.info("Deep Java pre-pass: computed (key {}), {} classes ({} from cache) from {} directories/jars, {} libraries; "
      + "class facts {} ms, summaries {} ms", key == null ? "cache off" : key, byName.size(), hits[0], binaries.size(), libraries.size(),
      loaded - start, System.currentTimeMillis() - loaded);
    return s;
  }

  /**
   * Binaries and libraries of the modules declared in the project settings ({@code sonar.modules} with
   * {@code <module>.sonar.java.binaries}, as the Maven and Gradle scanners pass them), nested modules included.
   */
  private void declaredModules(Path base, String prefix) throws IOException {
    for (String module : config.getStringArray(prefix + "sonar.modules")) {
      String p = prefix + module.trim() + ".";
      Path moduleBase = config.get(p + "sonar.projectBaseDir").map(base::resolve)
        .orElse(base.resolve(module.trim().replace(':', '_')));
      binaries.addAll(resolve(moduleBase, config.getStringArray(p + "sonar.java.binaries")));
      libraries.addAll(resolve(moduleBase, config.getStringArray(p + "sonar.java.libraries")));
      declaredModules(moduleBase, p);
    }
  }

  /** {@code target/test-classes} and {@code build/classes/<lang>/test} directories below {@code root}. */
  static List<Path> discoverTestDirs(Path root) throws IOException {
    List<Path> out = new ArrayList<>();
    try (Stream<Path> s = Files.walk(root)) {
      s.filter(Files::isDirectory).filter(d -> {
        String p = d.toString().replace('\\', '/');
        return (p.endsWith("/target/test-classes") || p.matches(".*/build/classes/[^/]+/test")) && !p.contains("/src/");
      }).forEach(d -> out.add(d.toAbsolutePath().normalize()));
    }
    return out;
  }

  /** {@code target/classes} and {@code build/classes/<lang>/main} directories anywhere below {@code root}. */
  static List<Path> discoverOutputDirs(Path root) throws IOException {
    List<Path> out = new ArrayList<>();
    Files.walkFileTree(root, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        Path name = dir.getFileName();
        Path parent = dir.getParent();
        if (name == null || parent == null || dir.equals(root)) {
          return FileVisitResult.CONTINUE;
        }
        if (SKIP_DIRS.contains(name.toString())) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        boolean mavenOut = name.toString().equals("classes") && parent.getFileName().toString().equals("target");
        boolean gradleOut = name.toString().equals("main") && parent.getParent() != null
          && parent.getParent().getFileName().toString().equals("classes")
          && parent.getParent().getParent() != null && parent.getParent().getParent().getFileName().toString().equals("build");
        if (mavenOut || gradleOut) {
          out.add(dir.toAbsolutePath().normalize());
          return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException e) {
        return FileVisitResult.CONTINUE;
      }
    });
    return out;
  }

  /** Paths relative to {@code base}; {@code *} and {@code **} wildcards are expanded. */
  static List<Path> resolve(Path baseDir, String[] patterns) throws IOException {
    List<Path> out = new ArrayList<>();
    for (String raw : patterns) {
      String p = raw.trim();
      if (p.isEmpty()) {
        continue;
      }
      String glob = p.replace('\\', '/');
      int wildcard = indexOfWildcard(glob);
      if (wildcard >= 0) {
        // walk only from the directory before the first wildcard
        int cut = glob.lastIndexOf('/', wildcard);
        Path root = cut < 0 ? baseDir : baseDir.resolve(glob.substring(0, cut + 1)).normalize();
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(cut + 1));
        if (Files.isDirectory(root)) {
          try (Stream<Path> s = Files.walk(root)) {
            s.filter(f -> m.matches(root.relativize(f))).map(f -> f.toAbsolutePath().normalize()).forEach(out::add);
          }
        }
      } else {
        Path f = baseDir.resolve(p).toAbsolutePath().normalize();
        if (Files.exists(f)) {
          out.add(f);
        }
      }
    }
    return out;
  }

  private static int indexOfWildcard(String s) {
    int star = s.indexOf('*');
    int question = s.indexOf('?');
    return star < 0 ? question : question < 0 ? star : Math.min(star, question);
  }

  private interface ClassConsumer {
    void accept(byte[] bytes) throws IOException;
  }

  private static void forEachClass(Collection<Path> roots, ClassConsumer consumer) throws IOException {
    for (Path root : roots) {
      if (Files.isDirectory(root)) {
        try (Stream<Path> s = Files.walk(root)) {
          for (Path f : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".class"))::iterator) {
            consumer.accept(Files.readAllBytes(f));
          }
        }
      } else if (root.toString().endsWith(".jar")) {
        try (ZipFile zip = new ZipFile(root.toFile())) {
          for (ZipEntry e : Collections.list(zip.entries())) {
            if (e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/")) {
              consumer.accept(zip.getInputStream(e).readAllBytes());
            }
          }
        }
      }
    }
  }

  private Path cacheFile() {
    Path dir = config.get(DeepJavaPlugin.CACHE_DIR).map(baseDir::resolve)
      .orElse(Path.of(System.getProperty("user.home"), ".sonar", "deepjava-cache"));
    return dir.resolve(sha256(baseDir.toAbsolutePath().toString().getBytes()).substring(0, 16) + ".bin");
  }

  /** Only our fact records and JDK collections may be deserialised from the cache file. */
  private static final ObjectInputFilter CACHE_FILTER = ObjectInputFilter.Config.createFilter(
    "io.github.snakeinprod.deepjava.prepass.ClassFacts*;java.util.*;java.lang.*;maxdepth=64;!*");

  @SuppressWarnings("unchecked")
  static Map<String, ClassFacts> readCache(Path file) {
    if (!Files.exists(file)) {
      return new HashMap<>();
    }
    try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
      in.setObjectInputFilter(CACHE_FILTER);
      return new HashMap<>((Map<String, ClassFacts>) in.readObject());
    } catch (IOException | ClassNotFoundException | RuntimeException e) {
      LOG.warn("Deep Java: ignoring unreadable cache {}", file);
      return new HashMap<>();
    }
  }

  static void writeCache(Path file, Map<String, ClassFacts> entries) {
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
        out.writeObject(new HashMap<>(entries));
      }
      Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      LOG.warn("Deep Java: cannot write cache {}: {}", file, e.getMessage());
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
