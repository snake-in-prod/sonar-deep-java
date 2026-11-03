package io.github.snakeinprod.deepjava.prepass;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Library knowledge from JSON files. Format:
 * <pre>
 * {
 *   "nullableReturns": ["java/util/Map#get", "java/lang/System#getProperty(Ljava/lang/String;)Ljava/lang/String;"],
 *   "guards": {"java/util/Map#get": ["containsKey", "keySet"]}
 * }
 * </pre>
 * A method entry is {@code owner#name} (any overload) or {@code owner#name(desc)ret}. Entries apply to subtypes.
 * A guard is a method name: if the caller also calls it on the same receiver, the null return is assumed handled.
 */
public final class LibraryModels {

  static final String DEFAULT_RESOURCE = "/models/default.json";

  private final Set<String> nullableReturns = new HashSet<>();
  private final Map<String, Set<String>> guards = new HashMap<>();
  private final Map<String, String> uncheckedThrows = new HashMap<>();
  private final Set<String> resourceFactories = new HashSet<>();

  /** A matched model entry. */
  public record Match(String entry, Set<String> guards) {
  }

  private static final class Json {
    List<String> nullableReturns = List.of();
    Map<String, List<String>> guards = Map.of();
    Map<String, String> uncheckedThrows = Map.of();
    List<String> resourceFactories = List.of();
  }

  public static LibraryModels load(List<Path> userFiles) throws IOException {
    LibraryModels models = new LibraryModels();
    try (InputStream in = LibraryModels.class.getResourceAsStream(DEFAULT_RESOURCE)) {
      models.add(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    for (Path p : userFiles) {
      try (Reader r = Files.newBufferedReader(p)) {
        models.add(r);
      }
    }
    return models;
  }

  private void add(Reader reader) {
    Json json = new Gson().fromJson(reader, Json.class);
    if (json.nullableReturns != null) {
      nullableReturns.addAll(json.nullableReturns);
    }
    if (json.guards != null) {
      json.guards.forEach((k, v) -> guards.computeIfAbsent(k, x -> new HashSet<>()).addAll(v));
    }
    if (json.uncheckedThrows != null) {
      uncheckedThrows.putAll(json.uncheckedThrows);
    }
    if (json.resourceFactories != null) {
      resourceFactories.addAll(json.resourceFactories);
    }
  }

  /** Unchecked exception a library method throws on bad input (e.g. {@code Integer.parseInt}), or null. */
  public String uncheckedThrow(String owner, String name, String desc) {
    String byName = owner + "#" + name;
    String t = uncheckedThrows.get(byName + desc);
    return t != null ? t : uncheckedThrows.get(byName);
  }

  /** The method returns a new resource the caller must close ({@code Files.newInputStream}). */
  public boolean isResourceFactory(String owner, String name, Hierarchy hierarchy) {
    for (String type : hierarchy.selfAndSupertypes(owner)) {
      if (resourceFactories.contains(type + "#" + name)) {
        return true;
      }
    }
    return false;
  }

  /** Looks up {@code owner#name desc} on the owner and its supertypes. */
  public Match nullableReturn(String owner, String name, String desc, Hierarchy hierarchy) {
    for (String type : hierarchy.selfAndSupertypes(owner)) {
      String byName = type + "#" + name;
      if (nullableReturns.contains(byName + desc) || nullableReturns.contains(byName)) {
        return new Match(byName, guards.getOrDefault(byName, Set.of()));
      }
    }
    return null;
  }
}
