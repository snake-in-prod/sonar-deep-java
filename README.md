# Deep Java

Whole-program Java rules for SonarQube Community Build.

The Community Build analyses Java one method at a time. Deep Java first reads the compiled classes of the
whole project, builds per-method summaries and a class hierarchy, and then reports defects that cross method
and file boundaries, as ordinary Sonar issues.

Status: early. The first rule (interprocedural nullness) works end to end. See [docs/SPEC.md](docs/SPEC.md)
for the full plan.

## Rules

| Key | What it finds | CWE |
|-----|---------------|-----|
| `deepjava:NullReturnDereference` | A method that can return `null` (directly, through other methods, or a library method such as `Map.get`) and its result is dereferenced without a check | 476, 690 |

Example:

```java
// Config.java
String host() { return props().get("host"); }   // Map.get may return null

// Client.java
int port(Config c) { return c.host().indexOf(':'); }
// "Config.host()" can return null (Config.java:14 via "Map.get()"); check the result before dereferencing it.
```

The rules are added to the built-in "Sonar way" Java profile.

## Install

Tested on SonarQube Community Build 26.7 (Java plugin 8.34) and 26.10 (Java plugin 8.44). The scanner must
run on Java 21 or later (SonarQube provisions it by default).

1. Download `sonar-deep-java-<version>.jar` from [Releases](https://github.com/snake-in-prod/sonar-deep-java/releases).
2. Copy it to `extensions/plugins/` of your SonarQube server and restart.
3. Analyse with compiled classes available (`sonar.java.binaries`). Maven and Gradle scanners set this.

## Settings

| Property | Default | Meaning |
|----------|---------|---------|
| `sonar.deepjava.models` | none | Extra library model JSON files (see below) |
| `sonar.deepjava.cacheDir` | `~/.sonar/deepjava-cache` | Per-class summary cache; unchanged classes are not re-analysed |
| `sonar.deepjava.maxStepsPerMethod` | `20000` | Data-flow budget per method |
| `sonar.deepjava.maxVirtualTargets` | `8` | Virtual calls with more implementations are ignored |
| `sonar.deepjava.statistics.minCallSites` | `5` | A `null` returned by project code is reported only if the method has at least this many call sites... |
| `sonar.deepjava.statistics.minRatio` | `0.8` | ...and its result is null-checked at least at this share of them |

### Library models

```json
{
  "nullableReturns": [
    "com/acme/Registry#lookup",
    "com/acme/Store#find(Ljava/lang/String;)Ljava/lang/Object;"
  ],
  "guards": {
    "com/acme/Registry#lookup": ["contains"]
  }
}
```

An entry is `owner#name` (all overloads) or `owner#name(descriptor)`, in JVM internal form, and also applies
to subtypes. A guard is a method name: if the caller also calls it on the same variable, the result is
treated as checked. Built-in models: [`src/main/resources/models/default.json`](src/main/resources/models/default.json).

## How it works

1. **Pre-pass** (once per analysis, shared by all modules): every class in the `sonar.java.binaries` of each
   module, and in any `target/classes` or `build/classes/*/main` directory under the project, is read with ASM.
   For each method returning a reference, a branch-sensitive data flow finds `return null` paths and which
   call results can be returned. Results are cached by class file hash.
2. **Solving**: call targets are resolved with class hierarchy analysis (project classes, library jars and
   the JDK), and library models are applied.
3. **Rules** run in sonar-java's normal per-file pass and look up the summaries for each call.

### Precision

Precision comes first. Not reported:

- a value compared with `null` on the path, or the same call compared with `null` in the method
  (`key() == null ? 0 : key().limit()`); a local is only reported when its first use is a dereference;
- `null` returned only when an argument or a field is null (`if (s == null) return null;`), only after a call
  that always throws (`fail(...); return null;`), or in a `catch (CloneNotSupportedException)`;
- `return null` defaults that subclasses override, and calls on library types resolved to project
  implementations (`Map.Entry.getValue()`);
- lookups whose content the author knows: computed keys (`map.get(id)`), no key (`queue.peek()`), constant
  tables (`static final` maps), locals created with their content (`new EnumMap<>(Map.of(...))`,
  `partitioningBy`), and guard calls (`containsKey`, `isEmpty`, ...) on the same receiver;
- standard JVM properties (`System.getProperty("os.name")`).

A `null` that comes from project code (not a library model) is reported only when the project itself
treats the method as nullable: its result is null-checked at most call sites (default 80% of at least 5).
The message then gives the counts, e.g. `null-checked at 4 of 5 call sites`.

## Benchmark

Mature libraries, analysed from their Maven Central class and source jars on SonarQube 26.7. Each issue was
reviewed by hand.

| Project | Classes | Issues | True positive | False positive |
|---------|--------:|-------:|--------------:|---------------:|
| commons-lang3 3.21.0 | 439 | 0 | 0 | 0 |
| commons-io 2.22.0 | 428 | 0 | 0 | 0 |
| jackson-databind 2.22.4 | 813 | 1 | 1 (probable: `getAccessor()` is null for a property with neither getter nor field) | 0 |
| kafka-clients 4.4.0 | 4345 | 4 | 0 | 4 (`configs.get(CONSTANT)`: Kafka's `ConfigDef` fills every key) |

Before the precision rules above, the same run gave 840 issues. Mature libraries have few real null bugs, so
these numbers measure noise, not recall. Recall on Juliet / OWASP Benchmark is still to do.

## Known limits

- Modules with non-standard output directories are only seen once their own analysis has started.
- Recursive call cycles are treated as non-null.
- Methods annotated `@Nullable`/`@CheckForNull` are left to sonar-java's `S2259`.

## Build

```sh
./mvnw verify    # JDK 21
```

The plugin jar is `target/sonar-deep-java-<version>.jar`.

## License

Apache-2.0
