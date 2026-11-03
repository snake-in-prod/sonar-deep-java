# Benchmarks

`Bench.java` runs the pre-pass on a directory of compiled classes and prints every finding of the bytecode rules
(taint, statistics, locking, exceptions, dead code) as `rule,path,line,message`. The syntax-tree rules need a
SonarQube analysis.

```sh
mvn -q compile
CP="target/classes:$(find ~/.m2 -name 'asm*-9.11.1.jar' | tr '\n' ':')$(find ~/.m2 -name 'gson-2.15.0.jar')"
javac -cp "$CP" -d /tmp/bench tools/benchmark/Bench.java
java -cp "$CP:/tmp/bench" Bench <classes-dir> <dir-of-library-jars> > findings.csv
```

## OWASP Benchmark 1.2

Compile `src/main/java/org/owasp/benchmark/testcode` of [BenchmarkJava](https://github.com/OWASP-Benchmark/BenchmarkJava)
with `javac -g -proc:none -sourcepath src/main/java` against the dependencies of its `pom.xml`, run `Bench`, then:

```sh
python3 tools/benchmark/owasp_score.py findings.csv BenchmarkJava/expectedresults-1.2.csv
```

## Juliet Test Suite for Java 1.3

Unzip the suite, compile `src/testcasesupport` and the CWE directories with `lib/*.jar`, run `Bench`, export the
syntax-tree findings of a SonarQube analysis in the same CSV format, then:

```sh
python3 tools/benchmark/juliet_score.py Java/src findings.csv
```

An issue in a method whose name contains `bad` counts as a true positive for that test case, in a `good` method as a
false positive.
