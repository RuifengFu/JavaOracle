# Java Oracle
A tool to generate test Oracle for Java programs.


## Config

- `git clone https://github.com/openjdk/jdk17u-dev`
- Download JavaDoc from https://docs.oracle.com/en/java/javase/17/docs/api/index.html and unzip it to `jdk17u-dev/jdk/doc`.
- `mkdir verify && cp -r jdk17u-dev/test verify`
- using java 23+

## Build

```bash
mvn -B -Dmaven.test.skip=true package
```

## Run

```bash
mvn -B -q exec:java -Dexec.mainClass=edu.tju.ista.llm4test.Main -Dexec.args="env"
mvn -B -q exec:java -Dexec.mainClass=edu.tju.ista.llm4test.Main -Dexec.args="execute java/lang"
```

## CI

- `Build Linux Artifact`: builds and uploads Linux jar artifacts on every push.
- `Test Latest JDK Commits`: configures environment and runs JavaOracle for testcases changed in the last 24 hours under `test/jdk/java` and `test/jdk/javax`.
