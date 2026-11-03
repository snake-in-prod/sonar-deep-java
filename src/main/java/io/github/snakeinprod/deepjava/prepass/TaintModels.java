package io.github.snakeinprod.deepjava.prepass;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Taint sources, sinks, propagators and sanitizers from {@code models/taint.json} plus user model files (the same
 * keys, in any file of {@code sonar.deepjava.models}). Methods are {@code owner#name}; {@code *} matches any owner
 * or name part. Sink argument indexes exclude the receiver, which is {@code -1}.
 * <p>ponytail: owners match exactly (no subtype lookup at scan time); list subtypes in the model when needed.
 */
public final class TaintModels {

  public record SinkModel(String kind, List<Integer> args) {
  }

  private static TaintModels defaults;

  private final Map<String, String> sources = new HashMap<>();
  private final Map<String, String> paramAnnotations = new HashMap<>();
  private final Set<String> listenerAnnotations = new HashSet<>();
  private final Map<String, SinkModel> sinks = new HashMap<>();
  private final Map<String, String> propagators = new HashMap<>();
  private final List<Pattern> sanitizers = new ArrayList<>();
  private final List<Pattern> wildcardSources = new ArrayList<>();
  private final List<String> wildcardSourceKinds = new ArrayList<>();
  private Pattern secretNames = Pattern.compile("(?!)");
  private final StringBuilder digestInput = new StringBuilder();

  public static synchronized TaintModels defaults() {
    if (defaults == null) {
      defaults = load(List.of());
    }
    return defaults;
  }

  public static TaintModels load(List<Path> userFiles) {
    TaintModels t = new TaintModels();
    try (InputStream in = TaintModels.class.getResourceAsStream("/models/taint.json")) {
      t.add(new InputStreamReader(in, StandardCharsets.UTF_8));
      for (Path p : userFiles) {
        try (Reader r = Files.newBufferedReader(p)) {
          t.add(r);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return t;
  }

  private void add(Reader reader) {
    JsonObject json = new Gson().fromJson(reader, JsonObject.class);
    digestInput.append(json);
    if (json.has("sources")) {
      for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("sources").entrySet()) {
        if (e.getKey().contains("*")) {
          wildcardSources.add(glob(e.getKey()));
          wildcardSourceKinds.add(e.getValue().getAsString());
        } else {
          sources.put(e.getKey(), e.getValue().getAsString());
        }
      }
    }
    if (json.has("sourceParameterAnnotations")) {
      json.getAsJsonObject("sourceParameterAnnotations").entrySet().forEach(e -> paramAnnotations.put(e.getKey(), e.getValue().getAsString()));
    }
    if (json.has("listenerAnnotations")) {
      json.getAsJsonArray("listenerAnnotations").forEach(e -> listenerAnnotations.add(e.getAsString()));
    }
    if (json.has("secretNames")) {
      secretNames = Pattern.compile(json.get("secretNames").getAsString());
    }
    if (json.has("sinks")) {
      for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("sinks").entrySet()) {
        JsonObject o = e.getValue().getAsJsonObject();
        List<Integer> args = new ArrayList<>();
        JsonArray a = o.getAsJsonArray("args");
        a.forEach(x -> args.add(x.getAsInt()));
        sinks.put(e.getKey(), new SinkModel(o.get("kind").getAsString(), List.copyOf(args)));
      }
    }
    if (json.has("propagators")) {
      json.getAsJsonObject("propagators").entrySet().forEach(e -> propagators.put(e.getKey(), e.getValue().getAsString()));
    }
    if (json.has("sanitizers")) {
      json.getAsJsonArray("sanitizers").forEach(e -> sanitizers.add(glob(e.getAsString())));
    }
  }

  private static Pattern glob(String s) {
    return Pattern.compile(Pattern.quote(s).replace("*", "\\E.*\\Q"));
  }

  /** Identifies the model set, for the summary cache key. */
  public String digest() {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(digestInput.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(h).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public String source(String owner, String name) {
    String kind = sources.get(owner + "#" + name);
    if (kind != null) {
      return kind;
    }
    String key = owner + "#" + name;
    for (int i = 0; i < wildcardSources.size(); i++) {
      if (wildcardSources.get(i).matcher(key).matches()) {
        return wildcardSourceKinds.get(i);
      }
    }
    return null;
  }

  public SinkModel sink(String owner, String name) {
    return sinks.get(owner + "#" + name);
  }

  /** {@code receiver}, {@code args}, {@code all}, {@code numeric}, or null. */
  public String propagation(String owner, String name) {
    String p = propagators.get(owner + "#" + name);
    return p != null ? p : propagators.get(owner);
  }

  public boolean isSanitizer(String owner, String name) {
    String key = owner + "#" + name;
    return sanitizers.stream().anyMatch(p -> p.matcher(key).matches());
  }

  public String parameterAnnotationSource(String annotation) {
    return paramAnnotations.get(annotation);
  }

  public boolean isListenerAnnotation(String annotation) {
    return listenerAnnotations.contains(annotation);
  }

  public boolean isSecretName(String name) {
    return name != null && secretNames.matcher(name).matches();
  }
}
