# 🐱 Dobby & Spring Essentials Quiz

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

Made for fun, giggles, and (just a little bit of) enlightenment

---

## Runnable samples

Every answer ships as a runnable Spring Boot app. Here for fun, and so nobody can
call me a nasty liar. Don't trust the answer key? Run it and see for yourself.

### Requirements & how to run
- JDK 21+ (tested on 21 and 25). Check: `java -version`
- No Gradle install needed — the wrapper downloads it. No DB needed — H2 in memory.
- ANSI colors work in a normal terminal. IntelliJ Run too.

```bash
./gradlew q01      # one sample directly
./gradlew quizAll  # every sample, in order
```

| Sample | What it shows |
|---|---|
| `q01` | a connection pinned for the whole payment call, and the same call moved out |
| `q02` | runner vs two listeners of one event, plus a listener that throws (sync and `@Async`) |
| `q03` | self-invocation: the proxy never sees `this.feedOnce()` |
| `q04` | circular constructor dependencies, and one side marked `@Lazy` (extra, not in the quiz) |
| `q05` | `@Transactional(timeout)` as a deadline, not a watchdog |
| `q06` | bean lifecycle order, drawn as a cat |
| `q07` | Hibernate dirty checking: an `UPDATE` with no `save()` |
| `q08` | a default bean with no `@Profile`, and a feature behind a profile nobody activated |

## Answer keys

Full versions with answers and explanations:

| Quiz | Ukrainian | English |
|---|---|---|
| #1 — what is under Spring's hood | [keys/QUIZ.md](keys/QUIZ.md) | [keys/QUIZ_EN.md](keys/QUIZ_EN.md) |
| #2 — data, Hibernate and config | [keys/QUIZ2.md](keys/QUIZ2.md) | [keys/QUIZ2_EN.md](keys/QUIZ2_EN.md) |

---

# Quiz yourself

No answers below. Check yourself against the [answer keys](#answer-keys) above.

# Quiz #1 — what is under Spring's hood

## 1. Friday, 17:00

Friday evening, 17:00, nobody is deploying anything... the planned rollout goes live behind a
feature flag (fair enough, who does that on a Friday). The promo has been running for two hours,
`/checkout` worked all week, nothing was deployed today.

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
- B) Hikari leaks connections — restart and move on
- C) Each checkout holds a connection for the whole payment call; the rest queue up and time out
- D) The pool is too small — `maximum-pool-size=200` and go home

## 2. Refactored ourselves into a corner

The cache warm-up lived in an `ApplicationRunner`, the discovery registration hung on
`ApplicationReadyEvent`. Someone said on review: "both are about startup, why two mechanisms?"

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

Pretty, right? Prod disagrees: the first 20 seconds after every deploy, p95 goes through the roof
and the cache misses everything. The event did not change — it was the right one all along.

- A) `ApplicationReadyEvent` is too early — you need `AvailabilityChangeEvent`
- B) Between two listeners of the same event there is no ordering guarantee: `publishUp()` legally
     runs first, and discovery hears "ready" with a cold cache
- C) The warm-up blocks the event — slap `@Async` on it and let it warm up in peace
- D) Within one bean Spring takes only the first `@EventListener` for an event

## 3. The warm-up that never made it to traffic

The warm-up stayed in the listener after all. Today `cat-service` answers 503, so
`loadTopCats()` throws `IllegalStateException`.

```java
@EventListener(ApplicationReadyEvent.class)
void warmUpCache() { cache.loadTopCats(); }    // boom

@EventListener(ApplicationReadyEvent.class)
void publishUp()   { discovery.register(); }
```

- A) The exception is logged, `publishUp()` runs anyway
- B) The remaining listeners of that event are skipped, `run()` rethrows, the context closes and
     the process exits with a non-zero code
- C) The context is already up, so the app keeps living, just without a cache
- D) Spring republishes the event

## 4. Dobby calls himself

```java
@Service
class FeedingService {

    public void feedTwice() {          // no annotation
        feedOnce();                    // this.feedOnce()
    }

    @Transactional
    public void feedOnce() { ... }     // annotation is here
}
```
You call `service.feedTwice()` from outside. Is there a transaction inside `feedOnce()`?

- A) Yes, a brand new one — the annotation is on the method being executed
- B) No transaction at all
- C) Yes, but only if `feedTwice()` is also `@Transactional`
- D) Startup error: `@Transactional` on a method called internally

> 🐱 *Dobby's note: whatever your answer is — don't forget to feed me!*

## 5. The transaction that watched the sun rise

Nightly job, 50 000 rows, one partner API call per row. Someone added
`@Transactional(timeout = 20)` "so it can never hang". At 09:00 the job is still running,
a connection has been open since 01:00, and there is no `TransactionTimedOutException`
in the log.

```java
@Transactional(timeout = 20)
public void nightRun() {
    for (Cat cat : repo.findAllDueForBilling()) {
        partnerApi.send(cat);      // no socket timeout configured
        cat.setBilled(true);
    }
}
```

- A) `timeout` only works with JTA, not with a plain JDBC data source
- B) It is a deadline, not a watchdog: checked when the next JDBC statement is issued.
     A thread parked in an HTTP call issues none, and the connection stays borrowed
- C) The timeout did fire at 20 s; the connection in the log belongs to another job
- D) Hikari `maxLifetime` should have closed that connection

## Bonus. What a cat is made of

Each lifecycle hook prints PART of a cat. The output itself **draws a cat**.
Which cat comes out?

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
🙃 A) constructor first, so whiskers on top · 🐱 B) ears first · 😹 C) ready before init · 🐈 D) runner after the event

---

# Quiz #2 — data, Hibernate and config

## 1. The migration that saved nothing

Before the real data migration we did a dry run on prod: read every row, normalise the names in
memory, count how many rows it would touch. No `save()`, no `flush()`. We looked at the report,
rolled the release back, went to bed. Next day: validation errors on names nobody had changed.

```java
@Transactional
public int preview() {
    int wouldChange = 0;
    for (Cat cat : repo.findAll()) {
        String normalised = cat.name.trim().toLowerCase();
        if (!normalised.equals(cat.name)) {
            cat.name = normalised;            // "just to print the diff"
            wouldChange++;
        }
    }
    return wouldChange;                       // no save() anywhere
}
```

- A) Nothing happens: without `save()` Hibernate knows nothing about the changes
- B) On commit Hibernate auto-flushes, compares the entities with their snapshots and issues an
     `UPDATE` for every changed row
- C) There will be an `UPDATE`, but only after an explicit `entityManager.flush()`
- D) An exception: you cannot modify an entity without `save()`

## 2. The profile that never was

Locally and on staging everything flies. On CI, with `spring.profiles.active=test`, the
application does not start at all. The mock was added "so the tests don't hit the real feeder".

```java
@Bean Feeder realFeeder() { return new RealFeeder(); }   // the default, no profile

@Profile("test")
@Bean Feeder mockFeeder() { return new MockFeeder(); }   // the mock for tests

@Bean CatService catService(Feeder feeder) { ... }
```

- A) Spring picks the profiled bean — a profile is more specific than a default
- B) `NoUniqueBeanDefinitionException` at startup: a bean without `@Profile` is active always,
     so there are two candidates
- C) `mockFeeder` overrides `realFeeder` — bean overriding
- D) Nothing: `@Profile("test")` is simply ignored outside tests

## 3. The feature nobody switched on

The nightly billing job is hidden behind the `prod` profile, and the scheduler collects jobs as a
collection. On dev nobody activates that profile.

```java
@Profile("prod")
@Bean NightJob billingJob() { ... }

@Bean Scheduler scheduler(List<NightJob> jobs) { ... }   // collection injection
```

- A) `NoSuchBeanDefinitionException` at startup
- B) Starts fine, with 0 jobs in the list — billing simply does not exist, and nobody will tell you
- C) Spring injects an empty proxy instead of the bean
- D) It blows up the first time the scheduler runs
