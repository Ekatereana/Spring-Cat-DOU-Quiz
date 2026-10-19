# 🐱 Dobby & Spring Essentials Quiz — answer key

```
    __________________________
   |  WELCOME TO SPRING QUIZ  |
   |__________________________|
       \o   o/
       /\_/\
      ( o.o )
       > ^ <
```

> For handing out to students: remove the **Answer** and **Output** blocks.
> Every item has a runnable app: `./gradlew qNN`.

---

## 1. Friday, 17:00  (`./gradlew q01`)

```java
@Transactional
public void checkout(String name) {
    repo.save(new Cat(name));
    paymentProvider.charge(name);   // external HTTP call, ~1.5 s
}
```
```
java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available,
request timed out after 30000ms (total=10, active=10, idle=0, waiting=37)
```

- A) The database is down, or out of `max_connections`
- B) Hikari leaks connections
- C) Each checkout holds a connection for the whole payment call; the rest queue up and time out
- D) The pool is too small — `maximum-pool-size=200`

**Answer: C.** A connection is borrowed when the transaction starts and returned on commit.
The payment call sits *inside* the transaction, so every request pins a connection for ~1.5 s
while the database does ~2 ms of work. With Boot's default `maximum-pool-size=10` the ceiling
is about 6 checkouts per second; everything above that waits in the pool queue and fails after
`connection-timeout` (default 30 s). The code did not change on Friday — the arrival rate did.

**What helps:** move the payment call out of the transaction (1500 ms → ~2 ms) ·
a read/connect timeout on the HTTP client · `spring.jpa.open-in-view=false`.
**What does not:** `maximum-pool-size=200` or more Tomcat threads — the queue just moves to
the database, and the pool must never exceed `max_connections / instances`.

**Output** (pool 5, timeout 2 s, 12 shoppers):
```
-- payment call INSIDE the transaction --
pool during the spike: active=5, idle=0, threads waiting=7
finished in 3036 ms: 10 ok, 2 failed
orders written: 10 of 12

-- payment call moved OUT of the transaction --
pool during the spike: active=0, idle=5, threads waiting=0
finished in 1506 ms: 12 ok, 0 failed
```

---

## 2. When is the cat REALLY awake?  (`./gradlew q02`)

- A) `ContextRefreshedEvent`
- B) `ApplicationStartedEvent`
- C) `ApplicationReadyEvent`
- D) `ApplicationContextInitializedEvent`

**Answer: C.** Startup order:
`ApplicationStartingEvent` → `ApplicationEnvironmentPreparedEvent` →
`ApplicationContextInitializedEvent` → `ApplicationPreparedEvent` →
`ContextRefreshedEvent` → `ApplicationStartedEvent` → *runners* → `ApplicationReadyEvent`.

- `ContextRefreshedEvent` — beans exist, nothing else is guaranteed. Fires again for child
  contexts and on an actuator `/refresh`, so a listener can run more than once.
- `ApplicationStartedEvent` — context is up, **runners have not run**: the cache is still cold.
- `ApplicationReadyEvent` — last one: runners finished, the server accepts traffic. Use it for
  warm-up checks, registering in discovery, starting consumers.
- `AvailabilityChangeEvent` (`ReadinessState.ACCEPTING_TRAFFIC`) is the Kubernetes-flavoured
  variant, and it can flip back to `REFUSING_TRAFFIC` later.

**Output:**
```
[event]   ContextRefreshedEvent   (beans created, context refreshed)
[event]   ApplicationStartedEvent (context up, runners NOT run yet)
[runner]  cache warmed up
[event]   ApplicationReadyEvent   (runners finished, app can serve traffic)
```

---

## 3. Dobby calls himself  (`./gradlew q03`)

```java
public void feedTwice() { feedOnce(); }   // no annotation, internal call
@Transactional public void feedOnce() { ... }
```

- A) Yes, a brand new one
- B) No transaction at all
- C) Yes, but only if `feedTwice()` is also `@Transactional`
- D) Startup error

**Answer: B.** `@Transactional` is implemented by a proxy (CGLIB here — the bean class is
`FeedingService$$SpringCGLIB$$0`). The proxy can only intercept calls that come **through**
it. `this.feedOnce()` never leaves the object, so no interceptor runs. Same trap for
`@Cacheable`, `@Async`, `@Retryable`, `@PreAuthorize`.

**Fixes:** move `feedOnce()` to a separate bean (cleanest) · inject the bean into itself
(`@Lazy FeedingService self`) · `((FeedingService) AopContext.currentProxy()).feedOnce()`
with `@EnableAspectJAutoProxy(exposeProxy = true)` · real AspectJ weaving.

> 🐱 *And most importantly — don't forget to feed me!*

**Output:**
```
call through the proxy: service.feedOnce()
feedOnce()   tx active = true
call through the proxy, then internally: service.feedTwice()
feedTwice()  tx active = false
feedOnce()   tx active = false
```

---

## 4. The cat needs the bowl, the bowl needs the cat  (`./gradlew q04`)

- A) Starts fine — Spring resolves cycles with its three-level cache
- B) Fails at startup: `BeanCurrentlyInCreationException`
- C) Starts, then throws on the first call
- D) Starts with a warning

**Answer: B.** Constructor injection needs a finished `BowlService` before it can build
`CatService`, and the other way round — unresolvable. Since Boot 2.6 circular references are
also forbidden by default (`spring.main.allow-circular-references=false`). Good news: it fails
at startup, not at 3 a.m. in production.

**Follow-up:** ✅ `@Lazy` on one constructor parameter (a proxy is injected, the real bean is
built on first call) · ✅ a third bean with the shared logic — the only fix that removes the
design problem · ✅ an `ApplicationEventPublisher` instead of the back-call ·
⚠️ field/setter injection — "works" only with `allow-circular-references=true` and leaves the
cycle in place, half-initialised beans included.

**Output:**
```
BeanCurrentlyInCreationException: Error creating bean with name 'BowlService':
Requested bean is currently in creation: Is there an unresolvable circular reference...
-- one side marked @Lazy --
bowl filled -> Dobby eats
```

---

## 5. The transaction that watched the sun rise  (`./gradlew q05`)

- A) `timeout` only works with JTA
- B) It is a deadline, not a watchdog — checked when the next JDBC statement is issued
- C) The timeout fired at 20 s; that connection belongs to another job
- D) Hikari `maxLifetime` should have closed it

**Answer: B.** Spring stores the deadline in the transaction's `ResourceHolder` and checks it
when the next statement goes out, passing the remaining seconds to `Statement.setQueryTimeout`.
No timer thread, nothing interrupts the application thread. So a loop that keeps querying dies
on the first statement after the deadline, while a loop parked in an HTTP call issues no
statement — nobody checks anything and the connection stays checked out of the pool for as long
as the socket read takes. `maxLifetime` does not help either: Hikari only retires **idle**
connections, never one in use.

**What bounds the damage:** connect/read timeouts on every HTTP and messaging client (the real
fix) · one short transaction per chunk, so the connection returns to the pool and progress is
kept · `idle_in_transaction_session_timeout` / `statement_timeout` on the database ·
Hikari `leakDetectionThreshold` for the log line at 01:01.
**What does not:** raising `timeout` to 600 — same bug, longer night.

**Output:**
```
-- loop keeps querying the database --
stopped after 2270 ms
TransactionTimedOutException: Transaction timed out: deadline was ...

-- loop waits 5 s for a third-party API --
   t+4s  pool active=1, idle=2        <- 2 s deadline, connection still held
stopped after 5010 ms
TransactionException: transaction timeout expired   <- only at commit, after the damage

-- fix: slow call outside, one short transaction per chunk --
   t+1s  pool active=0, idle=3
rows committed: 5
```

---

## 6. Lifecycle order — bonus  (`./gradlew q06`)

**Answer: A — the upside-down cat.** Initialisation order for a single bean:

1. constructor (dependencies injected) — whiskers
2. `@PostConstruct` — face
3. `InitializingBean.afterPropertiesSet()` — ears
4. (`@Bean(initMethod = ...)` would come next)
5. `ApplicationRunner` / `CommandLineRunner` — tail
6. `ApplicationReadyEvent` — purr

`@PostConstruct` runs **before** `afterPropertiesSet()` because annotation callbacks are
applied by `CommonAnnotationBeanPostProcessor` during `postProcessBeforeInitialization`, while
`afterPropertiesSet()` is part of the initialization step itself. Shutdown is the mirror image:
`ContextClosedEvent` → `@PreDestroy` → `DisposableBean.destroy()`.

**Output:**
```
 > ^ <
( o.o )
 /\_/\
 ~~~~~
  purr
```
