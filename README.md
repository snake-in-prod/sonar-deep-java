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

Requires SonarQube Community Build 25.x or 26.x (Java plugin 8.x).

1. Download `sonar-deep-java-<version>.jar` from the GitHub Actions build artifacts or releases.
2. Copy it to `extensions/plugins/` of your SonarQube server and restart.
3. Analyse with compiled classes available (`sonar.java.binaries`). Maven and Gradle scanners set this.

## Settings

| Property | Default | Meaning |
|----------|---------|---------|
| `sonar.deepjava.models` | none | Extra library model JSON files (see below) |
| `sonar.deepjava.cacheDir` | `~/.sonar/deepjava-cache` | Per-class summary cache; unchanged classes are not re-analysed |
| `sonar.deepjava.maxStepsPerMethod` | `20000` | Data-flow budget per method |
| `sonar.deepjava.maxVirtualTargets` | `8` | Virtual calls with more implementations are ignored |

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

1. **Pre-pass** (once per module, on first use): every class in `sonar.java.binaries` is read with ASM.
   For each method returning a reference, a branch-sensitive data flow finds `return null` paths and which
   call results can be returned. Results are cached by class file hash.
2. **Solving**: call targets are resolved with class hierarchy analysis (project classes, library jars and
   the JDK), and library models are applied.
3. **Rules** run in sonar-java's normal per-file pass and look up the summaries for each call.

Precision comes first: a value that is compared with `null` anywhere on the path is not reported, and a
local is only reported when its first use is a dereference.

## Known limits

- Summaries are per module: a call into another Maven/Gradle module of the same project is not resolved yet.
- Recursive call cycles are treated as non-null.
- Methods annotated `@Nullable`/`@CheckForNull` are left to sonar-java's `S2259`.

## Build

```sh
./mvnw verify    # JDK 21
```

The plugin jar is `target/sonar-deep-java-<version>.jar`.

## License

Apache-2.0
