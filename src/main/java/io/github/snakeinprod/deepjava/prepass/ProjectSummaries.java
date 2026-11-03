package io.github.snakeinprod.deepjava.prepass;

import io.github.snakeinprod.deepjava.DeepJavaPlugin;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.ScannerSide;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.config.Configuration;

/**
 * The pre-pass: reads all project bytecode once (lazily, on first use by a rule), with per-class results
 * cached by class file hash between analyses.
 */
@ScannerSide
public class ProjectSummaries implements Supplier<Summaries> {

  private static final Logger LOG = LoggerFactory.getLogger(ProjectSummaries.class);
  private static final int CACHE_FORMAT = 1;

  private final Configuration config;
  private final Path baseDir;
  private Summaries summaries;

  public ProjectSummaries(Configuration config, FileSystem fs) {
    this.config = config;
    this.baseDir = fs.baseDir().toPath();
  }

  @Override
  public synchronized Summaries get() {
    if (summaries == null) {
      try {
        summaries = build();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return summaries;
  }

  private Summaries build() throws IOException {
    long start = System.currentTimeMillis();
    int maxSteps = config.getInt(DeepJavaPlugin.MAX_STEPS).orElse(20000);
    int maxTargets = config.getInt(DeepJavaPlugin.MAX_TARGETS).orElse(8);
    List<Path> binaries = resolve(config.getStringArray("sonar.java.binaries"));
    List<Path> libraries = resolve(config.getStringArray("sonar.java.libraries"));
    List<Path> models = resolve(config.getStringArray(DeepJavaPlugin.MODELS));

    Path cacheFile = cacheFile();
    Map<String, ClassFacts> cache = readCache(cacheFile);
    Map<String, ClassFacts> used = new HashMap<>();
    List<ClassFacts> facts = new ArrayList<>();
    int[] hits = {0};
    forEachClass(binaries, bytes -> {
      String key = sha256(bytes) + ":" + maxSteps;
      ClassFacts f = cache.get(key);
      if (f != null) {
        hits[0]++;
      } else {
        f = BytecodeScanner.scan(bytes, maxSteps);
      }
      used.put(key, f);
      facts.add(f);
    });
    writeCache(cacheFile, used);

    Summaries s = new Summaries(facts, new Hierarchy(Summaries.byName(facts), libraries), LibraryModels.load(models), maxTargets);
    LOG.info("Deep Java pre-pass: {} classes ({} from cache) in {} ms", facts.size(), hits[0], System.currentTimeMillis() - start);
    return s;
  }

  /** Paths relative to the base directory; {@code *} and {@code **} wildcards are expanded. */
  List<Path> resolve(String[] patterns) throws IOException {
    List<Path> out = new ArrayList<>();
    for (String raw : patterns) {
      String p = raw.trim();
      if (p.isEmpty()) {
        continue;
      }
      if (p.contains("*") || p.contains("?")) {
        Path root = Path.of(p).isAbsolute() ? Path.of(p).getRoot() : baseDir;
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + p.replace('\\', '/'));
        try (Stream<Path> s = Files.walk(root)) {
          s.filter(f -> m.matches(Path.of(p).isAbsolute() ? f : baseDir.relativize(f))).forEach(out::add);
        }
      } else {
        Path f = baseDir.resolve(p);
        if (Files.exists(f)) {
          out.add(f);
        }
      }
    }
    return out;
  }

  private interface ClassConsumer {
    void accept(byte[] bytes) throws IOException;
  }

  private static void forEachClass(List<Path> roots, ClassConsumer consumer) throws IOException {
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

  private static Map<String, ClassFacts> readCache(Path file) {
    Map<String, ClassFacts> m = new HashMap<>();
    if (!Files.exists(file)) {
      return m;
    }
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
      if (in.readInt() != CACHE_FORMAT) {
        return m;
      }
      for (int n = in.readInt(); n > 0; n--) {
        m.put(in.readUTF(), ClassFacts.read(in));
      }
    } catch (IOException | RuntimeException e) {
      LOG.warn("Deep Java: ignoring unreadable cache {}", file);
      m.clear();
    }
    return m;
  }

  private static void writeCache(Path file, Map<String, ClassFacts> entries) {
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
        out.writeInt(CACHE_FORMAT);
        out.writeInt(entries.size());
        for (Map.Entry<String, ClassFacts> e : entries.entrySet()) {
          out.writeUTF(e.getKey());
          e.getValue().write(out);
        }
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
