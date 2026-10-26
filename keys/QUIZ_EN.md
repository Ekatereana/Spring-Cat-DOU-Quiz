# 🐱 Cat quiz: what is under Spring's hood

```
     ______________________
    |  SPRING  DETECTIVE   |
    |______________________|
         \o   o/   ___
         /\_/\    (___)
        ( -.- )    | |
         > ^ <
```

Meet **Senior-Tomato Dobby**, cat consultant. Shows up when everything is already on fire,
sniffs the logs, pours himself a coffee and says soothingly: "we'll fix it in a sec".

Ready to look under Spring's hood, into that burning abyss? Picture this: Friday, 17:00,
nobody deployed anything (honestly!), someone just switched checkout on behind a feature flag
(which was tested, obviously).

Few moments later:

```
java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available,
request timed out after 30000ms (total=10, active=10, idle=0, waiting=37)
```
any hypotheses? Then let's dig in -->

Five scenes, code, live samples, and exactly one right answer.

P.s. the proofs live here https://github.com/Ekatereana/Spring-Cat-DOU-Quiz — run the code yourself.
And yes, the tastiest bit is at the very end, saved for dessert.

> For students: strip the answer blocks.

---

## Case #1. Friday, 17:00  (`./gradlew q01`)

So picture it: Friday evening, 17:00, nobody is deploying anything... the planned rollout of
a new feature goes live behind a feature flag (fair enough, who does that on a Friday). The promo
has been running for two hours, `/checkout` worked all week, nothing was deployed today.

```java
@Transactional
public void checkout(String name) {
    repo.save(new Cat(name));
    paymentProvider.charge(name);   // external HTTP, ~1.5 s
}
```
```
java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available,
request timed out after 30000ms (total=10, active=10, idle=0, waiting=37)
```
What happened?

- A) The database is down, or `max_connections` is exhausted
- B) Hikari leaks connections — restart and move on
- C) Each checkout holds a connection for the whole payment call; the rest queue up and time out
- D) The pool is too small — `maximum-pool-size=200` and go home

**Right answer: C.** A connection is borrowed when the transaction starts and returned on commit.
The payment call sits **inside** it, so every request pins a connection for ~1.5 s while the
database does ~2 ms of work. With Boot's default `maximum-pool-size=10` the ceiling is about
6 checkouts per second; everything above that waits in the pool queue and dies on
`connection-timeout` (30 s). The code did not change on Friday — the arrival rate did.

**What to do:** move the payment call out of the transaction (1500 ms → ~2 ms) ·
connect/read timeouts on the HTTP client · `spring.jpa.open-in-view=false`.
**What not to do:** `maximum-pool-size=200` or more Tomcat threads — the queue just moves into
the database. The pool is never bigger than `max_connections / number of instances`.

---

## Case #2. Refactored ourselves into a corner  (`./gradlew q02`)

**Warm-up (in case you forgot)** — what Spring fires at startup:
`ApplicationStartingEvent` → `ApplicationEnvironmentPreparedEvent` →
`ApplicationContextInitializedEvent` → `ApplicationPreparedEvent` →
`ContextRefreshedEvent` → `ApplicationStartedEvent` → *runners* → `ApplicationReadyEvent`.
And the web server starts listening even before `ContextRefreshedEvent` — in `finishRefresh()`.

**The case:** the cache warm-up lived in an `ApplicationRunner`, the discovery registration hung
on `ApplicationReadyEvent`. Someone said on review: "both are about startup, why two mechanisms?"

**Before:**
```java
@Bean ApplicationRunner warmUp(Cache cache) {
    return args -> cache.loadTopCats();          // ~20 s
}

@EventListener(ApplicationReadyEvent.class)
void publishUp() { discovery.register(); }
```
**After:**
```java
@EventListener(ApplicationReadyEvent.class)
void warmUpCache() { cache.loadTopCats(); }      // ~20 s

@EventListener(ApplicationReadyEvent.class)
void publishUp()   { discovery.register(); }
```

Pretty, right? Prod disagrees. The first 20 seconds after every deploy: p95 through the roof
and nothing but cache misses.

The event did not change — it was the right one all along. So what broke?

- A) `ApplicationReadyEvent` is too early — you need `AvailabilityChangeEvent`
- B) Between two listeners of the **same** event there is no ordering guarantee: `publishUp()`
  legally runs first, and discovery hears "ready" with a cold cache
- C) The warm-up blocks the event — slap `@Async` on it and let it warm up in peace
- D) Within one bean Spring takes only the first `@EventListener` for an event

**Right answer: B.** The refactor removed an ordering guarantee and replaced it with nothing.

- Listeners of one event are collected by reflection; `AnnotationAwareOrderComparator` sorts
  only what carries `@Order`. The rest stay in whatever order the JVM handed over. It used to
  work by accident.
- Runners, on the other hand, *are* ordered against the event: `run()` calls `callRunners()`
  and only then publishes `ApplicationReadyEvent`.
- `@Async` (C) makes it worse: the registration then beats the warm-up **every** time.
- A is false: the port has been open since `finishRefresh()`. D is false: several listeners for
  one event in one class is perfectly fine.

**And how to do it nicely, if you really want it on events?** Ask for the order explicitly:

```java
@Order(1)
@EventListener(ApplicationReadyEvent.class)
void warmUpCache() { cache.loadTopCats(); }

@Order(2)                                      // registration always last
@EventListener(ApplicationReadyEvent.class)
void publishUp()   { discovery.register(); }
```

Works, but it is a convention you have to remember for every new listener: forget `@Order` once
and the newcomer joins the queue at random. A runner gives you the same thing for free.

**What to do:** warm-up back into a runner (cheapest) · `@Order` on **every** listener of that
event, registration last · `AvailabilityChangeEvent` + `ReadinessState` plus a readiness probe
if you want it the Kubernetes way.

---

## Case #3. The warm-up that never made it to traffic  (`./gradlew q02`)

**The case:** the warm-up stayed in the listener after all — "everything about startup lives in
events". Today `cat-service` answers 503, so `loadTopCats()` throws `IllegalStateException`.

```java
@EventListener(ApplicationReadyEvent.class)
void warmUpCache() { cache.loadTopCats(); }    // boom

@EventListener(ApplicationReadyEvent.class)
void publishUp()   { discovery.register(); }
```

What happens to the application?

- A) The exception is logged, `publishUp()` runs anyway
- B) The remaining listeners of that event are skipped, `run()` rethrows, the context closes and
  the process exits with a non-zero code
- C) The context is already up, so the app keeps living, just without a cache
- D) Spring republishes the event

**Right answer: B.** A listener runs synchronously, on the publisher's thread, so its exception
travels straight back. `ApplicationReadyEvent` is published by `SpringApplication.run()` itself,
and it does not catch anything softly: `handleRunFailure()` closes the context and rethrows.
`publishUp()` never runs at all.

```
 beans ──▶ port open ──▶ ContextRefreshed ──▶ Started ──▶ runners ──▶ Ready ──▶ we live
   1           2               2                 3           4         5
```

| # | Exception thrown here | What happens |
|---|---|---|
| 1 | constructor, `@PostConstruct`, `afterPropertiesSet()` | refresh fails, the port never even opened — the most honest crash |
| 2 | `ContextRefreshedEvent` listener | fails inside `refresh()`, but the port is already listening — a window for traffic |
| 3 | `ApplicationStartedEvent` listener | same, and the cache is cold: runners have not started |
| 4 | runner | `callRunners()` fails, `ApplicationReadyEvent` is never published |
| 5 | `ApplicationReadyEvent` listener | our case: the remaining listeners are skipped, the context closes, exit code ≠ 0 |
| — | any `@Async` listener | the exception is swallowed by `AsyncUncaughtExceptionHandler`: the pod is "ready" and broken |

That last row is answer C — but only with `@Async`. And it is the worst one: the pod is alive,
registered in discovery, has no cache, and traffic keeps coming. An honest crash in Kubernetes
is better.

---

## Case #4. Dobby calls himself  (`./gradlew q03`)

```java
public void feedTwice() { feedOnce(); }   // no annotation, internal call
@Transactional public void feedOnce() { ... }
```

You call `service.feedTwice()` from outside. Is there a transaction inside `feedOnce()`?

- A) Yes, a brand new one
- B) No transaction at all
- C) Yes, but only if `feedTwice()` is also `@Transactional`
- D) Startup error: `@Transactional` on a method called internally

**Right answer: B.** `@Transactional` works through a proxy (CGLIB here — the bean class is
`FeedingService$$SpringCGLIB$$0`). `this.feedOnce()` never leaves the object, so no interceptor
fires. The same trap applies to `@Cacheable`, `@Async`, `@Retryable`, `@PreAuthorize`.

**What to do:** move `feedOnce()` into its own bean (cleanest) · inject the bean into itself
(`@Lazy FeedingService self`) · `((FeedingService) AopContext.currentProxy()).feedOnce()` with
`@EnableAspectJAutoProxy(exposeProxy = true)` · real AspectJ weaving.

> 🐱 *And most importantly — don't forget to feed me.*

---

## Case #5. The transaction that watched the sun rise  (`./gradlew q05`)

**The ticket:** nightly job, 50 000 rows, one partner API call per row. Someone added
`@Transactional(timeout = 20)` "so it can never hang". At 09:00 the job is still running,
a connection has been open since 01:00, and there is no `TransactionTimedOutException` in the log.

```java
@Transactional(timeout = 20)
public void nightRun() {
    for (Cat cat : repo.findAllDueForBilling()) {
        partnerApi.send(cat);      // no socket timeout configured
        cat.setBilled(true);
    }
}
```

- A) `timeout` only works with JTA, and we are on plain JDBC
- B) It is a deadline, not a watchdog — it is checked when the next JDBC statement is issued
- C) The timeout did fire at 20 s; that connection belongs to another job
- D) Hikari `maxLifetime` should have closed it

**Right answer: B.** Spring stores the deadline in the `ResourceHolder` and checks it on the next
statement, passing the remaining seconds to `Statement.setQueryTimeout`. There is no timer thread,
nobody interrupts the application thread. A loop that keeps querying the database fails on the
first statement after the deadline; a loop parked in an HTTP call issues no statements at all —
there is nothing to check. `maxLifetime` does not save you either: Hikari only retires **idle**
connections.

**What to do:** connect/read timeouts on every HTTP and messaging client (the real fix) ·
one short transaction per chunk · `idle_in_transaction_session_timeout` / `statement_timeout`
on the database side · `leakDetectionThreshold` to get a log line at 01:01.
**What not to do:** raise `timeout` to 600 — same bug, longer night.

---

## Bonus. What a cat is made of  (`./gradlew q06`)

Every lifecycle callback prints a part of a cat. Which cat comes out?

```java
@Component
class CatBean implements InitializingBean {

    CatBean()                        { System.out.println(" > ^ < "); }  // whiskers
    @PostConstruct void post()       { System.out.println("( o.o )"); }  // face
    public void afterPropertiesSet() { System.out.println(" /\_/\ "); }  // ears
    @EventListener(ApplicationReadyEvent.class)
    void ready()                     { System.out.println("  purr  "); } // purring
}

@Bean ApplicationRunner runner() {
    return args -> System.out.println(" ~~~~~ ");                        // tail
}
```
```
A) upside down      B) proper cat       C) purring first    D) tail last
 > ^ <               /\_/\                purr               /\_/\
( o.o )             ( o.o )              /\_/\              ( o.o )
 /\_/\               > ^ <              ( o.o )              > ^ <
 ~~~~~               ~~~~~               > ^ <                purr
  purr                purr               ~~~~~               ~~~~~
```

**Right answer: A — the upside-down cat.** The order for a single bean:

1. constructor (dependencies already injected) — whiskers
2. `@PostConstruct` — face
3. `InitializingBean.afterPropertiesSet()` — ears
4. (`@Bean(initMethod = ...)` would come next)
5. `ApplicationRunner` / `CommandLineRunner` — tail
6. `ApplicationReadyEvent` — purring

`@PostConstruct` runs before `afterPropertiesSet()` because annotation callbacks are applied by
`CommonAnnotationBeanPostProcessor` in `postProcessBeforeInitialization`, while
`afterPropertiesSet()` is the initialization step itself. Shutdown is the mirror image:
`ContextClosedEvent` → `@PreDestroy` → `DisposableBean.destroy()`.

---

Cases closed, coffee finished. Dobby went to eat — he did his part.
