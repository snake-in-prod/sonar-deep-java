# ADR 0001: Installing Deep Java and its quality profile

- Status: accepted
- Date: 2026-11-04
- Applies to: SonarQube Community Build 26.7 and later (tested on 26.7 and 26.10), Deep Java 0.6.0

This record is written to be executed step by step, for example by an AI agent operating the SonarQube server.
Every command below was run against a SonarQube 26.7 container before this record was written.

## Context

- Deep Java is a SonarQube plugin (`deepjava`) that adds 58 Java rules. It needs the Java analyzer plugin
  (`java`, bundled with SonarQube) and compiled classes at analysis time.
- 56 of the rules add themselves to the built-in "Sonar way" Java profile through the Java plugin's API. Two
  rules (`IgnoredResultStatistical`, `MissingSuperCall`) are left out on purpose: on real projects most of their
  issues were intended code. `LombokUnreachableCode` is in the profile but reports only with
  `sonar.deepjava.referenceParity=true` (set it when comparing with a whole-program reference analyzer).
- The specification asks for a profile "Sonar way + selected sonar-findbugs rules + Deep Java".
  `docs/deep-java-profile.xml` holds the rules beyond Sonar way: the findbugs families `NP_*`, `OS_*`, `ODR_*`,
  `OBL_*`, `RV_*`, `DMI_*`, `EC_*`, `SE_*`, `UWF_*`, `IS2_INCONSISTENT_SYNC`, all of fb-contrib and Find Security
  Bugs, and 57 Deep Java rules (all but `MissingSuperCall`).
- A static copy of Sonar way would drift from the server's Java plugin (on 26.7 a copy taken from a newer Java
  plugin missed 69 rules). Profile inheritance does not drift.
- The server may also run the community branch plugin (`sonarqube-community-branch-plugin`). The two plugins do
  not interact; the branch plugin's `-javaagent` settings stay as they are.

## Decision

1. Install the released plugin jar in `extensions/plugins/` and restart SonarQube.
2. Create the "Deep Java" profile by restoring `docs/deep-java-profile.xml`, then make **Sonar way its parent**.
   The profile therefore always equals the server's current Sonar way plus the extra rules.
3. Make "Deep Java" the default Java profile. Projects that need plain Sonar way can still be assigned to it;
   it already contains the 47 default Deep Java rules.
4. Installing the sonar-findbugs plugin is optional. Without it, the restore skips the findbugs rules and the
   profile works with Sonar way + Deep Java.

## Procedure

Variables used below (set them first):

```sh
SONAR_URL=http://localhost:9000          # server URL
SONAR_ADMIN=admin:<password>             # an administrator login, or "<token>:" for a user token
VERSION=0.6.0
```

### 1. Install the plugin

```sh
curl -fL -o sonar-deep-java-$VERSION.jar \
  https://github.com/snake-in-prod/sonar-deep-java/releases/download/v$VERSION/sonar-deep-java-$VERSION.jar
```

Copy the jar into the server's plugin directory and restart:

- Plain install: `cp sonar-deep-java-$VERSION.jar <SONARQUBE_HOME>/extensions/plugins/`, then restart the service.
- Docker: copy it into the `extensions` volume, e.g.
  `docker cp sonar-deep-java-$VERSION.jar <container>:/opt/sonarqube/extensions/plugins/ && docker restart <container>`.

Remove older `sonar-deep-java-*.jar` files from that directory first; two versions of one plugin stop the server.

Wait until the server is up:

```sh
until curl -s $SONAR_URL/api/system/status | grep -q '"status":"UP"'; do sleep 5; done
```

### 2. Verify the plugin

```sh
curl -s -u $SONAR_ADMIN $SONAR_URL/api/plugins/installed | grep -o '"key":"deepjava"[^}]*"version":"[^"]*"'
curl -s -u $SONAR_ADMIN "$SONAR_URL/api/rules/search?repositories=deepjava&ps=1" | grep -o '"total":[0-9]*'
```

Expected: the `deepjava` plugin with the installed version, and `"total":58`.

### 3. Create the "Deep Java" profile

Download the profile file of the same release and restore it:

```sh
curl -fL -o deep-java-profile.xml \
  https://raw.githubusercontent.com/snake-in-prod/sonar-deep-java/v$VERSION/docs/deep-java-profile.xml
curl -s -u $SONAR_ADMIN -X POST -F backup=@deep-java-profile.xml $SONAR_URL/api/qualityprofiles/restore
```

Expected: `"name":"Deep Java"` and `"ruleFailures":0`. `ruleSuccesses` is 57 without sonar-findbugs and larger
with it. If a "Deep Java" profile already exists, the restore updates it.

Make Sonar way its parent, then the default:

```sh
curl -s -o /dev/null -w '%{http_code}\n' -u $SONAR_ADMIN -X POST \
  "$SONAR_URL/api/qualityprofiles/change_parent?language=java&qualityProfile=Deep%20Java&parentQualityProfile=Sonar%20way"
curl -s -o /dev/null -w '%{http_code}\n' -u $SONAR_ADMIN -X POST \
  "$SONAR_URL/api/qualityprofiles/set_default?language=java&qualityProfile=Deep%20Java"
```

Expected: `204` twice.

### 4. Verify the profile

```sh
curl -s -u $SONAR_ADMIN "$SONAR_URL/api/qualityprofiles/search?language=java" \
  | python3 -c 'import json,sys; [print(p["name"], p["activeRuleCount"], p.get("parentName",""), "default" if p["isDefault"] else "") for p in json.load(sys.stdin)["profiles"]]'
```

Expected: `Deep Java <n> Sonar way default`, where `<n>` is at least the active rule count of Sonar way.

### 5. Analyse

Deep Java reads compiled classes, so analyse after the build:

- Maven: `mvn verify sonar:sonar` (or `mvn compile sonar:sonar`).
- Gradle: `./gradlew build sonar`.
- sonar-scanner: pass `-Dsonar.java.binaries=<classes dir>` and, if possible, `-Dsonar.java.libraries=<jars>`.

The scanner log shows `Deep Java pre-pass: <n> classes ... in <t> ms`. If it shows `0 classes`, the binaries are
missing and only the syntax-tree rules run.

Optional settings (project or global, *Administration > Deep Java*): `sonar.deepjava.models`,
`sonar.deepjava.cacheDir`, `sonar.deepjava.maxStepsPerMethod`, `sonar.deepjava.maxVirtualTargets`,
`sonar.deepjava.statistics.minCallSites`, `sonar.deepjava.statistics.minRatio` (see the README).

## Rollback

```sh
curl -s -u $SONAR_ADMIN -X POST "$SONAR_URL/api/qualityprofiles/set_default?language=java&qualityProfile=Sonar%20way"
curl -s -u $SONAR_ADMIN -X POST "$SONAR_URL/api/qualityprofiles/delete?language=java&qualityProfile=Deep%20Java"
```

Then delete the jar from `extensions/plugins/` and restart. Issues already raised by Deep Java rules are closed
by the next analysis.

## Consequences

- Upgrading SonarQube or its Java plugin updates the Sonar way part of the profile automatically.
- Upgrading Deep Java: replace the jar, restart, and restore the new `deep-java-profile.xml` (step 3) to pick up
  new rules; the parent stays Sonar way.
- The scanner must run on Java 21 or later; SonarQube 26.x provisions such a runtime by default.
- The first analysis of a large project builds the summary cache (`~/.sonar/deepjava-cache` on the scanner host);
  later analyses re-read only changed classes.
