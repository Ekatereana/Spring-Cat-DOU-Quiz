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

## 1. When is the cat REALLY awake?  (`./gradlew q01`)

Dobby's app warms a cache in an `ApplicationRunner`. "Service is up" must be published
only when the cache is warm and traffic can be served.

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
- `ApplicationReadyEvent` — last one: runners finished, the web server accepts traffic.
  Use it for warm-up checks, registering in discovery, starting consumers.
- `AvailabilityChangeEvent` (`ReadinessState.ACCEPTING_TRAFFIC`) is the Kubernetes-flavoured
  variant of the same thing, and it can flip back to `REFUSING_TRAFFIC` later.

**Output:**
```
[event]   ContextRefreshedEvent   (beans created, context refreshed)
[event]   ApplicationStartedEvent (context up, runners NOT run yet)
[runner]  cache warmed up
[event]   ApplicationReadyEvent   (runners finished, app can serve traffic)
```

---

## 2. Dobby calls himself  (`./gradlew q02`)

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

**Output:**
```
call through the proxy: service.feedOnce()
feedOnce()   tx active = true
call through the proxy, then internally: service.feedTwice()
feedTwice()  tx active = false
feedOnce()   tx active = false
```

---

## 3. The cat that stayed in the database  (`./gradlew q03`)

```java
class NoFoodException extends Exception { }      // note the parent class

@Transactional
public void feedDobby() throws NoFoodException {
    repo.save(new Cat("Dobby"));
    throw new NoFoodException("bowl is empty");
}

@Transactional
public void feedShadow() {
    repo.save(new Cat("Shadow"));
    throw new IllegalStateException("bowl is empty");
}
```
Both methods are called, both throw, both exceptions are caught outside. Which cats are in the table?

- A) Neither — an exception always rolls the transaction back
- B) Only `Dobby`
- C) Only `Shadow`
- D) Both of them

**How to tell them apart in the snippet:** `NoFoodException extends Exception` directly, so it
is **checked** — and that is exactly why the method needs `throws NoFoodException` in its
signature. `IllegalStateException` sits under `RuntimeException`, so it is **unchecked** and
needs no `throws`. The hierarchy: everything under `Exception` is checked **except** the
`RuntimeException` subtree; `Error` is unchecked too.

**Answer: B.** Spring's default rollback rule is `RuntimeException` **or** `Error`. A checked
exception is treated as a business outcome and the transaction **commits**. (Plain JTA/EJB
behaves the same way; it is a Spring default, not a JPA rule.)

**Fix:** `@Transactional(rollbackFor = Exception.class)`, or throw an unchecked exception.
Since Spring 6 you can also declare it once in `@Transactional(rollbackForClassName = ...)`
or centrally via a custom `TransactionAttributeSource`.

**Output:**
```
rows after a CHECKED exception:   1   <- committed!
rows after an UNCHECKED exception: 0  <- rolled back
rows with rollbackFor = Exception.class: 0  <- rolled back
```

---

## 4. Nobody called `save()`  (`./gradlew q04`)

**Answer: B — `DOBBY`.** Inside a transaction the entity returned by `findById` is **managed**.
At flush time (before the commit, or before a query that touches the same table) Hibernate
compares it with the loaded snapshot and issues an `UPDATE` for every changed field.
`save()` only matters for *new* entities — for a managed one it is a no-op.

This is the "accidental write" bug: a mapper or a log line mutates an entity and the row
changes in production.

**Follow-up — what actually prevents it:**
- ✅ `@Transactional(readOnly = true)` — Hibernate switches the session to `FlushMode.MANUAL`
  and skips dirty checking (and the driver/DB may route to a replica)
- ✅ a DTO projection — you never hold a managed entity in the first place (best option)
- ✅ `entityManager.detach(cat)` — works, but easy to forget
- ❌ `propagation = SUPPORTS` — if a transaction is already there, the flush still happens
- ❌ `repo.flush()` — that *forces* the write, the exact opposite

**Output (`show-sql`):**
```
select c1_0.id,c1_0.name from cat c1_0 where c1_0.id=?
update cat set name=? where id=?          <- nobody asked for this
name in DB now: DOBBY
-- the same code under readOnly = true --
select c1_0.id,c1_0.name from cat c1_0 where c1_0.id=?
name in DB now: shadow
```

---

## 5. Explain the failure: the toys are gone  (`./gradlew q05`)

**Answer: B.** `cat.toys` is a lazy `PersistentBag`. The transaction (and with it the
persistence context / the JDBC connection) ended when `load()` returned, so the proxy has no
session to load from → `LazyInitializationException`.

**Fixes that stay surgical:**
- `@Query("select c from Cat c join fetch c.toys where c.id = :id")`
- `@EntityGraph(attributePaths = "toys")` on the repository method
- a DTO / interface projection with exactly the fields the caller needs
- do the work inside the transaction (the service returns a finished result, not an entity)

**Why the other options are bad:**
- `fetch = EAGER` fixes this one call and slows down every other query that loads a `Cat`
- `spring.jpa.open-in-view = true` (Boot's default, and the warning you see at startup)
  hides the bug: the session is held open for the whole HTTP request, lazy loads fire from
  the view layer, and the connection stays checked out of the pool until the response is written

**Output:**
```
cat loaded fine: Dobby
LazyInitializationException: failed to lazily initialize a collection of role:
quiz.q05.Cat.toys: could not initialize proxy - no Session
with 'join fetch c.toys': toys = 2
```

---

## 6. One findAll(), four queries  (`./gradlew q06`)

**Answer: B.** Classic N+1: 1 select for the cats, then one select per cat to initialise
`toys` (3 cats → 4 statements). With 1000 cats it is 1001 round trips.

**Fixes:**
- `@EntityGraph(attributePaths = "toys")` or `JOIN FETCH` → 1 statement
- `@BatchSize(size = 50)` / `hibernate.default_batch_fetch_size` → 1 + N/50, and it is the
  only option that scales when you fetch several collections at once
- a DTO projection with an aggregate (`select c.id, count(t)`) when you only need the number
- ⚠️ `JOIN FETCH` on **two** collections at once gives a cartesian product — batch size
  or `@EntityGraph` + `distinct` instead
- ❌ `fetch = EAGER` does not remove N+1: `findAll()` still loads the collection per row.
  It only moves the problem and makes every other query heavier.

**Output:**
```
findAll() + cat.toys.size():   toys = 6, SQL statements = 4   (1 + N)
@EntityGraph("toys"):          toys = 6, SQL statements = 1
```

---

## 7. Explain the failure: I caught the exception  (`./gradlew q07`)

**Answer: B.** `audit()` is `@Transactional` with the default `Propagation.REQUIRED`, so it
**joins** the caller's transaction instead of starting its own. When a `RuntimeException`
leaves it, the transaction interceptor marks the shared transaction `rollbackOnly`. Catching
the exception in the caller does not clear that flag — at commit time the transaction manager
sees `rollbackOnly` and throws `UnexpectedRollbackException`. Everything, including the cat,
is rolled back.

**Fixes:**
- `Propagation.REQUIRES_NEW` on the optional work — its own transaction and its own
  connection, so its failure cannot poison the caller (watch the pool: it holds two
  connections at once)
- `@Transactional(noRollbackFor = AuditException.class)` if the failure is genuinely harmless
- do not call it inside the transaction at all: publish an event and handle it with
  `@TransactionalEventListener(phase = AFTER_COMMIT)`
- if the inner call is just a helper, drop `@Transactional` from it entirely

**Output:**
```
caught and ignored: audit backend is down
UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only
cats in DB: 0
-- audit moved to REQUIRES_NEW --
cats in DB: 1
```

---

## 8. The cat needs the bowl, the bowl needs the cat  (`./gradlew q08`)

**Answer: B.** With constructor injection Spring must finish building `BowlService` before it
can construct `CatService`, and the other way round — unresolvable:
`BeanCurrentlyInCreationException`, and since Boot 2.6 circular references are forbidden by
default (`spring.main.allow-circular-references=false`). The app never starts — which is the
good news: the failure is at startup, not at 3 a.m. in production.

**Follow-up:**
- ✅ `@Lazy` on one constructor parameter — a proxy is injected, the real bean is built on
  first use
- ✅ a third bean holding the shared logic — the only fix that removes the design problem
- ✅ an `ApplicationEventPublisher` instead of the back-call
- ⚠️ field / setter injection — "works" only with
  `spring.main.allow-circular-references=true`; Spring fills the dependency after
  construction, so the cycle survives as a hidden half-initialised-bean risk

**Output:**
```
BeanCurrentlyInCreationException: Error creating bean with name 'BowlService':
Requested bean is currently in creation: Is there an unresolvable circular reference...
-- one side marked @Lazy --
bowl filled -> Dobby eats
```

---

## 9. Friday, 17:00  (`./gradlew q09`)

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
- C) Every checkout holds a connection for the whole payment call; the rest queue up and die
- D) The pool is too small — `maximum-pool-size=200`

**Answer: C.** A connection is borrowed when the transaction starts and returned when it
commits. The payment call sits *inside* the transaction, so each request pins one connection
for ~1.5 s even though the database does ~2 ms of work. With Boot's default
`maximum-pool-size=10` the whole application can process about `10 / 1.5 s ≈ 6` checkouts per
second. Everything above that waits in the pool queue and fails after
`connection-timeout` (default **30 s**) with the message above — and 30 s of waiting means the
Tomcat threads are gone too, so endpoints that never touch the database start failing as well.

Nothing changed in the code on Friday. Only the arrival rate did: the pool settings encode an
assumption about traffic, and the promo broke it.

**Follow-up — what actually helps:**
- ✅ move the payment call out of the transaction — the single biggest win: the transaction
  shrinks from 1500 ms to ~2 ms and the same pool serves hundreds of checkouts per second
- ✅ a read/connect timeout on the HTTP client — bounds the worst case instead of inheriting
  "forever" from the partner
- ✅ `spring.jpa.open-in-view=false` — otherwise the connection is held until the response is
  rendered, not until the service method returns
- ⚠️ `maximum-pool-size` — size it on purpose (`connections ≈ cores × 2` for the DB, and never
  more than `max_connections / instances`), but a bigger pool only moves the queue from the
  application to the database
- ❌ a bigger Tomcat thread pool — more threads fighting over the same 10 connections

**Output:**
```
pool size = 5, connection-timeout = 2000 ms, payment call = 1500 ms, shoppers = 12

-- payment call INSIDE the transaction --
pool during the spike: active=5, idle=0, threads waiting=7
finished in 3036 ms: 10 ok, 2 failed
SQLTransientConnectionException: HikariPool-1 - Connection is not available,
request timed out after 2005ms (total=5, active=5, idle=0, waiting=0)
orders written: 10 of 12

-- payment call moved OUT of the transaction --
pool during the spike: active=0, idle=5, threads waiting=0
finished in 1506 ms: 12 ok, 0 failed
orders written: 12 of 12
```

---

## 10. The transaction that watched the sun rise  (`./gradlew q10`)

```java
@Transactional(timeout = 20)
public void nightRun() {
    for (Cat cat : repo.findAllDueForBilling()) {
        partnerApi.send(cat);      // no socket timeout configured
        cat.setBilled(true);
    }
}
```
At 09:00 the job is still running, a connection has been open since 01:00, and there is no
`TransactionTimedOutException` in the log.

- A) `timeout` only works with JTA
- B) It is a deadline, not a watchdog — it is only checked when the next JDBC statement is issued
- C) The timeout fired at 20 s; that connection belongs to another job
- D) Hikari `maxLifetime` should have closed it

**Answer: B.** Spring stores the deadline in the `ResourceHolder` for the transaction and
checks it when the next statement goes out, passing the remaining seconds to
`Statement.setQueryTimeout(...)`. There is no timer thread, and nothing interrupts the
application thread. So:

- a loop that **keeps querying** does die — on the first statement after the deadline
  (`TransactionTimedOutException: Transaction timed out: deadline was ...`)
- a loop parked in an **HTTP call** issues no statement, so nobody checks anything; the
  connection stays checked out of the pool for as long as the socket read takes — all night,
  if the client has no read timeout
- `maxLifetime` does not help: Hikari only retires **idle** connections, never one that is in use
- a `timeout` value is also a *maximum*, never a guarantee: the work is still rolled back at
  the end, so the whole night of processing is lost anyway

**Follow-up — what actually bounds the damage:**
- ✅ connect/read timeouts on every HTTP and messaging client (this is the real fix)
- ✅ one short transaction per chunk instead of one transaction around the loop — the
  connection goes back to the pool between chunks and progress is kept
- ✅ `idle_in_transaction_session_timeout` / `statement_timeout` on the database — the last
  line of defence, enforced by someone other than your JVM
- ✅ Hikari `leakDetectionThreshold` — turns "a connection is open since 01:00" into a log line
  at 01:01
- ❌ raising `timeout` to 600 — same bug, longer night

**Output:**
```
-- version 1: the loop keeps querying the database --
   t+1s  pool active=1, idle=2
stopped after 2270 ms
TransactionTimedOutException: Transaction timed out: deadline was ...

-- version 2: the loop waits 5 s for a third-party API --
   t+1s  pool active=1, idle=2
   t+2s  pool active=1, idle=2
   t+3s  pool active=1, idle=2
   t+4s  pool active=1, idle=2      <- 2 s deadline, connection still held
stopped after 5010 ms
TransactionException: transaction timeout expired   <- only at commit, after the damage

-- the fix: slow call outside, one short transaction per chunk --
   t+1s  pool active=0, idle=3
finished after 5038 ms, 5 iterations, no exception
rows committed: 5
```

---

## 11. Lifecycle order — bonus  (`./gradlew q11`)

**Answer: A — the upside-down cat.** Initialisation order for a single bean:

1. constructor (dependencies injected) — whiskers
2. `@PostConstruct` — face
3. `InitializingBean.afterPropertiesSet()` — ears
4. (`@Bean(initMethod = ...)` would come next)
5. `ApplicationRunner` / `CommandLineRunner` — tail
6. `ApplicationReadyEvent` — purr

`@PostConstruct` runs **before** `afterPropertiesSet()` because annotation-based callbacks are
applied by `CommonAnnotationBeanPostProcessor` during `postProcessBeforeInitialization`,
while `afterPropertiesSet()` is part of the initialization step itself. Shutdown is the
mirror image: `ContextClosedEvent` → `@PreDestroy` → `DisposableBean.destroy()`.

**Output:**
```
 > ^ <
( o.o )
 /\_/\
 ~~~~~
  purr
```
