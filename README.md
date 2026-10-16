# ratelimit-gateway

A Redis-backed rate limiting library and a reverse-proxy gateway built on it, designed to **stay correct under concurrency** and to **behave predictably when Redis is gone**.

- Four algorithms (token bucket, fixed window, sliding window log, sliding window counter), each a single atomic Lua script.
- Limits per API key, per client IP and per route, loaded from a YAML file and **hot-reloaded** with validation.
- A gateway that proxies to an upstream service and answers `429` with `Retry-After` and `RateLimit-*` headers.
- Explicit **fail-open / fail-closed** behaviour per rule, with a circuit breaker and tight timeouts.
- Correctness is proven by tests that run against a real Redis in Docker (Testcontainers), including a test that freezes and kills Redis under load.
- JMH benchmarks for the limiter and a k6 script for the gateway.

Stack: Java 21 (built and tested on Java 25), Spring Boot 3.5, Lettuce, Resilience4j, Redis 7.

## Contents

1. [Quick start](#quick-start)
2. [How it works](#how-it-works)
3. [Algorithms compared](#algorithms-compared)
4. [Configuring policies](#configuring-policies)
5. [Gateway behaviour and headers](#gateway-behaviour-and-headers)
6. [When Redis is down](#when-redis-is-down)
7. [Using the limiter as a library](#using-the-limiter-as-a-library)
8. [Tests: what is proven and how](#tests-what-is-proven-and-how)
9. [Benchmarks](#benchmarks)
10. [Project layout](#project-layout)
11. [Known limitations](#known-limitations)

## Quick start

You need Java 21+, Maven 3.9+ and Docker.

```bash
# 1. Redis and a dummy upstream service
docker run -d --name redis -p 6379:6379 redis:7-alpine
mvn -q compile exec:java -Dexec.mainClass=io.ratelimit.dev.DummyService
#    (or from your IDE: run io.ratelimit.dev.DummyService, it listens on :9000)
#    Leave it running and open a second terminal for the next steps.

# 2. The gateway on :8080, reading ./policies.yml
mvn spring-boot:run

# 3. Try it
curl -i -H "X-API-Key: demo" http://localhost:8080/hello
```

The first response looks like this:

```
HTTP/1.1 200
RateLimit-Limit: 150
RateLimit-Remaining: 149
RateLimit-Reset: 1
```

Hammer it and you will see `429 Too Many Requests` with a `Retry-After` header. Edit `policies.yml` while the gateway runs and the new limits apply within about a second, with no restart.

Useful settings (all under `gateway.*`, set in `application.yml`, as `-Dgateway.x=y` or as environment variables like `GATEWAY_REDIS_URI`):

| Property | Default | Meaning |
|---|---|---|
| `upstream` | `http://localhost:9000` | Where requests are proxied to |
| `redis-uri` | `redis://localhost:6379` | Redis to use |
| `policy-file` | `policies.yml` | Policy file, watched for changes |
| `policy-poll-interval` | `1s` | How often the file is checked |
| `api-key-header` | `X-API-Key` | Header carrying the API key |
| `trust-forwarded-for` | `false` | Use the first `X-Forwarded-For` entry as the client IP. Only enable behind a proxy you control, otherwise clients can spoof their IP |
| `redis.connect-timeout` | `200ms` | Redis connect timeout |
| `redis.command-timeout` | `100ms` | Per Redis command timeout |
| `breaker.window-size` | `20` | Calls the breaker looks at |
| `breaker.minimum-calls` | `10` | Calls needed before the breaker may open |
| `breaker.failure-rate-percent` | `50` | Failure rate that opens the breaker |
| `breaker.open-duration` | `5s` | How long the breaker stays open before probing |
| `breaker.half-open-calls` | `3` | Probe calls allowed while half-open |

`GET /_gateway/status` returns the breaker state, the number of degraded checks, the number of active rules and the last policy error (or `null`).

## How it works

```
 client ──► GatewayFilter ──► for each matching rule ──► RedisGuard ──► Lua script in Redis
              │                                           │ (circuit breaker + timeouts)
              │ 429 / 503                                 └─ on failure: rule's FAIL_OPEN / FAIL_CLOSED
              ▼
         reverse proxy ──► upstream service
```

1. The filter finds every rule that matches the request (API key, client IP, route).
2. For each one it asks the limiter, via `RedisGuard`, for a decision. The first denial returns `429`.
3. If all rules allow the request, it is proxied to the upstream with the most constrained rule's `RateLimit-*` headers added.
4. If Redis cannot answer, the rule's failure mode decides (see [When Redis is down](#when-redis-is-down)).

### Why a Lua script makes it correct

A limiter must read the current state, decide, and write the new state as **one indivisible step**. With separate `GET` then `SET` commands, two clients can both read "1 token left" and both be allowed. Redis runs a Lua script to completion before it handles any other command, so check-and-decrement cannot interleave. Each limiter is one script (`src/main/resources/lua/`), called with `EVALSHA`.

Time comes from Redis itself (`TIME` inside the script), never from the client, so clocks on gateway instances cannot disagree and a client cannot gain tokens through clock skew.

## Algorithms compared

All four are implemented as Lua scripts and selectable per rule with `algorithm:`.

| | **Fixed window** | **Sliding window log** | **Sliding window counter** | **Token bucket** |
|---|---|---|---|---|
| Idea | Count requests in each aligned window (`:00-:01`, `:01-:02`, ...). | Keep a timestamp for every allowed request; count those in the last `window`. | Weight the previous fixed window by how much of it still overlaps, add the current count. | Bucket refills at a steady rate up to a capacity; each request takes a token. |
| **Memory per key** | O(1): one counter (plus one for the next window) | **O(limit)**: one sorted-set member per allowed request. 1,000,000 req/h means up to 1,000,000 members per key | O(1): two counters | O(1): one small hash (`tokens`, `ts`) |
| **Accuracy** | Exact within each aligned window, but windows are arbitrary | **Exact**: never more than `limit` in any rolling window | Approximate: assumes the previous window's requests were evenly spread. Cloudflare reported ~0.003% of requests wrongly allowed or denied | Exact for its own definition: at most `capacity + rate x time` over any interval |
| **Boundary burst** | **Up to 2x the limit** across a window edge (limit at `:00.9s` plus limit at `:01.1s`) | None | Strongly reduced. Bounded by the estimate error, not 2x | Bounded by `capacity`, a deliberate and configurable burst. No window edge exists |
| Allows deliberate bursts | No | No | No | **Yes**: set `burst` above `limit` |
| Redis ops per check | `GET` + `INCRBY` + `PEXPIRE` | `ZREMRANGEBYSCORE` + `ZCARD` + `ZADD` + `PEXPIRE` (+ `ZRANGE`); cost grows with trimmed entries | 2x `GET` + `INCRBY` + `PEXPIRE` | `HMGET` + `HSET` + `PEXPIRE` |
| `Retry-After` quality | Exact (time to window end) | Exact (time until oldest entry expires) | Estimated | Exact (time until enough tokens) |
| Best for | Cheap coarse quotas where a 2x edge burst is acceptable | Small limits that must be strict (login attempts, SMS) | High volume, per-key limits that need to be nearly strict at low cost | General API limiting where short bursts are fine, steady rate matters |

**Rule of thumb:** use token bucket by default; sliding log when the limit is small and exactness matters; sliding counter when the limit is large and you want near-exact windows cheaply; fixed window only when you do not care about edge bursts.

### Boundary-burst, concretely

With a limit of 100 per minute, a fixed window lets a client send 100 requests at 12:00:59 and another 100 at 12:01:00. That is 200 requests in two seconds, double the intended rate. The sliding log never allows more than 100 in any 60 second span. The sliding counter at 12:01:00 still counts about all of the previous window's 100, so the second burst is almost entirely refused. The token bucket cannot be drained and refilled that way, because tokens return at the refill rate.

## Configuring policies

Policies live in a YAML file (`policies.yml` by default).

```yaml
rules:
  - name: per-key            # unique; part of the Redis key
    scope: API_KEY           # API_KEY | IP | ROUTE
    match: "*"               # "*" = separate bucket per key; or one specific key
    algorithm: TOKEN_BUCKET  # TOKEN_BUCKET (default) | FIXED_WINDOW | SLIDING_LOG | SLIDING_COUNTER
    limit: 100               # requests per window
    window: 1s               # 500ms, 1s, 1m, 1h
    burst: 150               # TOKEN_BUCKET only: bucket capacity, must be >= limit
    onRedisFailure: FAIL_OPEN   # FAIL_OPEN (default) | FAIL_CLOSED

  - name: per-ip
    scope: IP
    match: "*"
    algorithm: SLIDING_COUNTER
    limit: 300
    window: 1m

  - name: checkout-route
    scope: ROUTE
    match: /api/checkout/**  # Spring path pattern; ONE bucket shared by all callers
    algorithm: SLIDING_LOG
    limit: 20
    window: 1s
    onRedisFailure: FAIL_CLOSED
```

| Scope | `match` | Who shares a bucket |
|---|---|---|
| `API_KEY` | `*` or a specific key | each distinct key has its own bucket (with `*`); requests with no API key header are not subject to the rule |
| `IP` | `*` or a specific address | each distinct client IP has its own bucket (with `*`) |
| `ROUTE` | path pattern such as `/api/orders/**` | **every caller** shares one bucket for the pattern |

All rules that match a request are applied. The request must pass every one of them.

### Hot reload and validation

The file is checked every `policy-poll-interval` (default 1s). On change:

- The new file is fully parsed and validated first. If valid, the rules are swapped atomically. Quota state lives in Redis, so a reload does not reset anyone's counters, and a raised or lowered limit applies to the very next request.
- If invalid, **the change is rejected and the previous rules stay active**. The error is logged once and is visible at `/_gateway/status` as `policyError`. Fix the file and the error clears.
- If the file is invalid at **startup**, the gateway refuses to start.

Every problem is reported at once, with the rule it belongs to:

```
Invalid rate limit policy (3 problems):
  - rules[0] (bad): 'scope' is 'USER', must be one of [API_KEY, IP, ROUTE]
  - rules[0] (bad): 'limit' must be a positive integer
  - rules[1] (bad): 'burst' only applies to TOKEN_BUCKET, not FIXED_WINDOW
```

Checked: valid YAML, non-empty `rules`, unique names, valid enum values, positive integer `limit`, parseable `window`, `burst` only for token bucket and not below `limit`, valid route patterns, and unknown fields (to catch typos like `limt`).

## Gateway behaviour and headers

| Situation | Response |
|---|---|
| Allowed | Upstream response, plus `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` |
| Denied | `429` with `Retry-After` (whole seconds, at least 1), the `RateLimit-*` headers and a JSON body `{"error":"rate_limited","rule":"...","retryAfterMs":...}` |
| Redis unavailable, rule is `FAIL_OPEN` | Request proxied normally, header `X-RateLimit-Degraded: fail-open`, no `RateLimit-*` headers (no fabricated numbers) |
| Redis unavailable, rule is `FAIL_CLOSED` | `503` with `Retry-After`, header `X-RateLimit-Degraded: fail-closed`, body `{"error":"limiter_unavailable",...}` |
| Upstream unreachable | `502` |

Header names follow the IETF `draft-ietf-httpapi-ratelimit-headers`. `RateLimit-Reset` is in seconds. When several rules match, the headers describe the rule with the fewest remaining requests.

The proxy forwards method, path, query string, headers (minus hop-by-hop ones), and request and response bodies as streams. It adds `X-Forwarded-For`. It runs on virtual threads, so waiting on Redis or the upstream does not hold an OS thread.

## When Redis is down

This is a decision you have to make on purpose. Each rule says what to do through `onRedisFailure`:

| Mode | What happens | Pick it when |
|---|---|---|
| `FAIL_OPEN` (default) | Requests are let through **unlimited** | Availability matters more than protection: public read APIs, anything where an outage of the limiter must not become an outage of your product |
| `FAIL_CLOSED` | Requests are rejected with `503` | Protection matters more than availability: payments, login, SMS/e-mail sending, expensive calls where unlimited traffic could cause real damage |

Mixed per route is the normal setup. The shipped `policies.yml` fails open for the general key and IP limits and fails closed for `/api/checkout/**`.

### Circuit breaker and timeouts

Waiting on a dead Redis on every request would turn a Redis outage into a slow outage of everything. Two mechanisms prevent that:

1. **Timeouts.** Connecting times out after 200ms and each command after 100ms. While the connection is down, commands are rejected immediately instead of queued.
2. **Circuit breaker (Resilience4j).** One breaker for all Redis calls (they share one Redis). Once at least 10 of the last 20 calls have been made and 50% failed, it **opens**: for 5 seconds no Redis call is attempted and rules answer instantly according to their failure mode. It then goes **half-open**, lets 3 probe calls through, and **closes** again if they succeed. All numbers are configurable under `gateway.breaker.*`.

Redis does not need to be reachable for the gateway to start: the connection is opened on first use.

### What a recovering system does

When Redis comes back, the breaker's probe calls succeed and normal limiting resumes. Limiter state that existed in Redis is used again. If Redis lost its data, buckets start full: limits are enforced again immediately but with fresh quotas.

This is **tested**, not just documented. See [RedisFailureTest](src/test/java/io/ratelimit/RedisFailureTest.java).

## Using the limiter as a library

The `io.ratelimit.limiter` package has no Spring dependency, only Lettuce.

```java
RedisConnection redis = new RedisConnection("redis://localhost:6379",
        new GatewayProperties.Redis(Duration.ofMillis(200), Duration.ofMillis(100)));
RedisScriptRunner runner = new RedisScriptRunner(redis);

RateLimiter limiter = Limiters.create(runner,
        new LimiterSpec(Algorithm.TOKEN_BUCKET, 50, Duration.ofSeconds(1), 100)); // 50/s, burst 100

Decision d = limiter.tryAcquire("rl:signup:" + userId);
if (!d.allowed()) {
    // wait d.retryAfterMs() before trying again
}
```

`Decision` carries `allowed`, `limit`, `remaining`, `retryAfterMs` and `resetMs`.

## Tests: what is proven and how

Run everything (needs Docker for most tests; they skip with a clear message if Docker is missing):

```bash
mvn test
```

| Ticket | Test | What it proves |
|---|---|---|
| RL-1 | [LimiterConcurrencyTest](src/test/java/io/ratelimit/LimiterConcurrencyTest.java) | 100 parallel clients, real Redis in a container. (a) All 100 fire at the same instant at a limit of 50: **exactly 50** are allowed, for every algorithm. (b) 3 seconds of sustained load at 50/s: allowed requests never exceed `capacity + rate x elapsed`, a hard upper bound that follows from the algorithm (a bucket cannot produce more tokens than that). Window algorithms never exceed `limit x windows touched`. |
| RL-2 | same test class | The same checks run for fixed window, sliding log and sliding counter |
| RL-3 | [GatewayHotReloadTest](src/test/java/io/ratelimit/GatewayHotReloadTest.java), [PolicyLoaderTest](src/test/java/io/ratelimit/PolicyLoaderTest.java) | Raising a limit on a running gateway takes effect without restart; an invalid edit is rejected, reported at `/_gateway/status`, and the old rules keep working; every kind of invalid config produces a specific message |
| RL-4 | [GatewayProxyTest](src/test/java/io/ratelimit/GatewayProxyTest.java) | Proxies method, path, query and body to a dummy service; `RateLimit-*` headers on success; `429` with `Retry-After` on exhaustion; independent limits per API key; shared route bucket |
| RL-5 | [RedisFailureTest](src/test/java/io/ratelimit/RedisFailureTest.java) | With traffic running, Redis is **frozen** (`docker pause`, so connections hang and timeouts are exercised) and then **killed**. Asserts that the breaker opens, the `FAIL_OPEN` route **never returns a non-200** throughout, the `FAIL_CLOSED` route returns `503` with `Retry-After`, answers come back without waiting on Redis, and after unfreezing the breaker closes and limiting resumes |

Why the bounds are honest: the sustained test measures time on the client, which is always at least the time Redis saw, so the bound can only be too generous by the few milliseconds of setup, never too tight. The result of one run on the development machine was: 201 allowed, 1200 denied, 3.25s elapsed, bound 213.

If a machine is so slow that the load never saturates the limiter, the sustained test is **skipped with an explanation** rather than passing without having proven anything.

## Benchmarks

### Read this first: the hardware

All numbers below come from one modest laptop-class machine, with everything on it at once (load generator, gateway, dummy upstream and Redis):

| | |
|---|---|
| CPU | AMD A8-7410 APU, 4 cores / 4 threads |
| RAM | 15 GB, of which only ~1.5 GB was free during the runs |
| OS | Windows 11 Pro |
| Java | Oracle JDK 25.0.3 (code compiled for 21) |
| Redis | Redis 7.4.11 (`redis:7-alpine`) in Docker Desktop on the same machine, reached through Docker's port forwarding on `127.0.0.1:6390` |
| Tools | JMH 1.37, k6 v2.2.0 |

**Treat these as a worked example, not as the library's ceiling.** Redis behind Docker Desktop port forwarding on a loaded 4-core machine costs several milliseconds per call, which dominates everything the limiter itself does. On a server with Redis on the same host or a nearby network, expect far better numbers. Re-run the benchmarks on your own hardware and replace these tables. Raw results are committed in [bench/results/](bench/results/).

### Limiter micro-benchmark (JMH)

[LimiterBenchmark](src/test/java/io/ratelimit/bench/LimiterBenchmark.java) calls `tryAcquire` in a loop against a real Redis. `baseline` is the same loop with no limiter call, so the gap to it is the price of limiting. Limits are set so high that every call is allowed, and each thread uses its own key. Settings: 1 fork, 2 warm-up iterations of 3s, 4 measured iterations of 4s.

**1 thread**

| Variant | Throughput (ops/s) | p50 latency | p99 latency |
|---|---:|---:|---:|
| baseline (no limiter) | 213,511,270 | ~0.0001 ms | 0.0003 ms |
| token bucket | 87 | 5.65 ms | 74.13 ms |
| fixed window | 87 | 6.10 ms | 62.60 ms |
| sliding log | 146 | 6.19 ms | 59.77 ms |
| sliding counter | 28 | 7.29 ms | 128.63 ms |

**16 threads**

| Variant | Throughput (ops/s) | p50 latency | p99 latency |
|---|---:|---:|---:|
| baseline (no limiter) | 570,049,686 | ~0.0001 ms | 0.0003 ms |
| token bucket | 853 | 13.78 ms | 161.74 ms |
| fixed window | 1,093 | 11.91 ms | 138.94 ms |
| sliding log | 1,037 | 13.32 ms | 134.22 ms |
| sliding counter | 1,132 | 12.17 ms | 150.47 ms |

How to read it honestly:

- The cost of limiting here is **one Redis round trip**. The in-process work is negligible (the baseline is hundreds of millions of ops per second), so what is measured is the path to Redis on this machine.
- **The four algorithms are indistinguishable.** At 16 threads their p50 is within 16% of each other and every difference is well inside the run-to-run noise (single fork, short iterations, loaded machine). The 1 thread sliding counter result (28 ops/s) is a noise outlier, not a property of the algorithm. Do not choose an algorithm from these tables; choose it from the [comparison above](#algorithms-compared) (memory, accuracy, boundary behaviour). On a fast network the extra Redis commands of the sliding log would show; here they are hidden.
- 16 threads gives about 10x the throughput of 1 thread with the same p50 per call, as expected when calls are waiting on the network and pipeline over one connection.
- p99 values are large because the machine was under memory pressure and the tail includes garbage collection and Docker forwarding hiccups.

Run it yourself:

```bash
docker run --rm -d --name redis -p 6379:6379 redis:7-alpine
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test

# Linux / macOS
java -cp "target/test-classes:target/classes:$(cat target/cp.txt)" org.openjdk.jmh.Main LimiterBenchmark -t 1  -rf json -rff bench/results/jmh-1-thread.json
java -cp "target/test-classes:target/classes:$(cat target/cp.txt)" org.openjdk.jmh.Main LimiterBenchmark -t 16 -rf json -rff bench/results/jmh-16-threads.json
```

On Windows the classpath is too long for `cmd.exe`; put it in an argument file (`-cp "..."` inside `target/jmh.args`, forward slashes, `;` separators) and call `java @target/jmh.args org.openjdk.jmh.Main ...`. To use a different Redis add `-jvmArgsAppend "-DREDIS_URI=redis://host:port"` to the JMH command (the benchmark runs in a forked JVM, so a plain `-D` on the launcher does not reach it). The default is `redis://127.0.0.1:6379`; make sure that is the Redis you think it is (see the note on validity below).

### Gateway load test (k6)

[bench/gateway.js](bench/gateway.js) sends `GET /ping` with one of 1,000 random API keys from 32 virtual users for 30 seconds, after a 40 second warm-up. Three setups, so the cost of proxying and the cost of limiting can be separated:

| Setup | What it is |
|---|---|
| **Direct** | k6 straight to the dummy upstream: the ceiling |
| **Gateway, proxy only** | gateway with [policies-none.yml](bench/policies-none.yml): proxies, but no rule matches, so there is no Redis call |
| **Gateway + limiter** | gateway with [policies-bench.yml](bench/policies-bench.yml): a token bucket per API key checked on every request, limits too high to ever reject |

| Setup | Throughput (req/s) | Mean | p50 | p95 | p99 | Failed requests |
|---|---:|---:|---:|---:|---:|---:|
| Direct to upstream | 1,026 | 26.4 ms | 11.8 ms | 96.2 ms | 264.4 ms | 0 |
| Gateway, proxy only | 95 | 274.2 ms | 118.8 ms | 1,031.5 ms | 2,775.1 ms | 0 |
| Gateway + limiter | 142 | 211.1 ms | 153.3 ms | 521.1 ms | 1,258.4 ms | 0 |

What this does and does not say:

- **The limiter was verified to be on the request path.** During the "Gateway + limiter" run Redis executed 7,318 limiter scripts; during "proxy only" it executed none. No request failed and no limiter check was degraded in either run.
- **The gateway hop is expensive on this machine.** Compared with calling the upstream directly, throughput fell by roughly an order of magnitude. That is the JDK `HttpClient`, Tomcat and a second hop on a 4-core CPU that is also running k6, the upstream, Redis and Docker.
- **The cost of the limiter itself cannot be resolved here.** The "with limiter" run came out *faster* than "proxy only". That is not a real speed-up; it means run-to-run noise on this machine is larger than the limiter's overhead. Compare the two on a quiet machine, with several repetitions, before drawing a conclusion.
- Throughput through the gateway keeps rising for the first 30 seconds or more while the JVM warms up. **Always warm up**; the script does.

#### Making sure a benchmark is valid

A benchmark that quietly measures the wrong thing is worse than none, so [bench/run-k6.ps1](bench/run-k6.ps1) refuses to produce results when:

- port 8080 or 9000 is already in use (a stale server would answer instead of the one under test),
- any limiter check was degraded (Redis timed out, so the gateway silently failed open),
- the "with limiter" run did not make Redis execute at least 1,000 limiter scripts, or the "proxy only" run made it execute any.

It also uses a 2 second Redis command timeout instead of the 100 ms production default, because Redis behind Docker Desktop on this machine regularly exceeded 100 ms under load, which made the limiter fail open and the run meaningless. Set `-CommandTimeout 100ms` on a machine where that holds.

Run it yourself (Windows, from the repo root; needs `mvn package -DskipTests` first):

```powershell
docker run -d --rm --name redis -p 6390:6379 redis:7-alpine    # use a port nothing else is listening on
mvn -q package -DskipTests
./bench/run-k6.ps1 -RedisUri redis://127.0.0.1:6390 -RedisContainer redis
```

On Linux or macOS start `DummyService` and the gateway as in the Quick start (with `--gateway.policy-file=bench/policies-none.yml` or `bench/policies-bench.yml`) and run `k6 run -e TARGET=http://localhost:8080 bench/gateway.js`.

## Project layout

```
policies.yml                          sample policy
bench/                                k6 script, benchmark policies, recorded JMH results
src/main/resources/lua/               the four Lua scripts
src/main/java/io/ratelimit/
  limiter/                            algorithms, LuaScript, RedisScriptRunner (no Spring)
  policy/                             PolicyLoader (validation), PolicyRegistry (hot reload), CompiledRule
  gateway/                            GatewayFilter (proxy + 429), RedisGuard (breaker + fail modes), config
  dev/DummyService.java               tiny upstream for demos, tests and benchmarks
src/test/java/io/ratelimit/           tests and the JMH benchmark
```

## Known limitations

- **Single Redis node.** The scripts build some keys inside Lua (window indexes for fixed window and sliding counter). That is fine for one node or a replica set but is not Redis Cluster safe. Cluster support would need hash tags and passing every key in `KEYS`.
- **Rules are checked one by one.** If a request passes rule A and is then denied by rule B, A has still consumed a unit. This errs on the side of limiting.
- **API keys appear in Redis key names.** Treat Redis as trusted. Hash keys first if that is not acceptable for you.
- **`RateLimit-*` headers follow an IETF draft** that has changed over time. Treat the exact names as subject to change.
- **No metrics endpoint yet**, only `/_gateway/status` and logs.
- **Cold start on slow machines.** The first requests after startup (class loading, JIT) can take longer than the 100 ms Redis command timeout, in which case those first checks fail open (or closed, per rule) and count as degraded. Raise `gateway.redis.command-timeout` or warm the gateway before sending real traffic.
- **Sliding log memory** grows with the limit. Do not use it for limits in the millions.
- **The gateway is a simple HTTP/1.1 reverse proxy.** No WebSockets, no response caching, no retries.
