# Benchmarks

The benchmark suite is local and deterministic. It does not open a network
connection, contact a public RPC provider, or require credentials.

`BenchmarkRemoteExecution` compares the request-per-item baseline with one
JSON-RPC batch and with the same batch path through rate admission. Its
`operationCount` parameter runs both a one-operation and a 100-operation
workload. `BenchmarkRemoteResultCache` measures cache hit, miss, and serialized
admission for 1 KiB and 64 KiB JSON values.

Compile benchmark classes and build their test dependency classpath:

```bash
mvn -pl trino-web3-runtime clean test-compile
mvn -pl trino-web3-runtime dependency:build-classpath \
    -Dmdep.includeScope=test \
    -Dmdep.outputFile=target/jmh-classpath.txt
```

Run with the same Java 23 installation used by Maven:

```bash
JAVA_23_BIN=/path/to/java-23/bin/java
JMH_CP="trino-web3-runtime/target/test-classes:trino-web3-runtime/target/classes:$(< trino-web3-runtime/target/jmh-classpath.txt)"
"$JAVA_23_BIN" -cp "$JMH_CP" org.openjdk.jmh.Main \
    '.*BenchmarkRemoteExecution.*|.*BenchmarkRemoteResultCache.*'
```

Report average latency, throughput derived from the JMH score, and the
workload parameter for every result. For production comparisons also record
remote request count, transferred bytes, and throttling events from the
corresponding deterministic runtime metrics; benchmark results must not be
compared across different Trino, Java, or provider configurations without
recording those versions and limits.
