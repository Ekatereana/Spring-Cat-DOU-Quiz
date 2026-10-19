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

## 9. Lifecycle order — bonus  (`./gradlew q09`)

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
