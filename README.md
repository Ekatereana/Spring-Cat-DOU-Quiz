# 🐱 Dobby & Spring Essentials Quiz

```
    __________________________
   |  WELCOME TO SPRING QUIZ  |
   |__________________________|
       \o   o/
       /\_/\
      ( o.o )
       > ^ <
```

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
./gradlew q01      # one question directly
./gradlew quizAll  # all six, in order
```

## Answer keys

Full versions with answers, explanations and expected output:
- 🇺🇦 Ukrainian — [keys/QUIZ.md](keys/QUIZ.md)
- 🇬🇧 English — [keys/QUIZ_EN.md](keys/QUIZ_EN.md)

---

# Quiz yourself

Six questions, no answers. Check yourself against the [answer keys](#answer-keys) above.

## 1. Friday, 17:00

The promo went live two hours ago. `/checkout` worked all week. Nothing was deployed today.

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

## 2. When is the cat REALLY awake?

Dobby's app warms a cache in an `ApplicationRunner`. You must publish "service is up"
to the discovery server — but only when the cache is warm and traffic can be served.

```java
@EventListener(???.class)
void publishUp() { discovery.register(); }
```

- A) `ContextRefreshedEvent`
- B) `ApplicationStartedEvent`
- C) `ApplicationReadyEvent`
- D) `ApplicationContextInitializedEvent`

## 3. Dobby calls himself

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

## 4. The cat needs the bowl, the bowl needs the cat

```java
@Service class CatService  { CatService(BowlService bowl) { } }
@Service class BowlService { BowlService(CatService cat)  { } }
```
Spring Boot 3, default settings. What happens?

- A) Starts fine — Spring resolves cycles with its three-level cache
- B) Fails at startup: `BeanCurrentlyInCreationException`
- C) Starts, then throws on the first call
- D) Starts with a warning in the log

**Follow-up:** which of these break the cycle?
`@Lazy` on one constructor parameter · field injection ·
`spring.main.allow-circular-references=true` · a third bean with the shared logic

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

## 6. Lifecycle order — bonus

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
A) Upside down   B) Proper cat    C) Purring first  D) Tail last
 > ^ <            /\_/\             purr             /\_/\
( o.o )          ( o.o )            /\_/\           ( o.o )
 /\_/\            > ^ <            ( o.o )           > ^ <
 ~~~~~            ~~~~~             > ^ <             purr
  purr             purr             ~~~~~            ~~~~~
```
🙃 A) constructor first, so whiskers on top · 🐱 B) ears first · 😹 C) ready before init · 🐈 D) runner after the event
