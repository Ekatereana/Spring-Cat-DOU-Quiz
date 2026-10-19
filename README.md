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
./gradlew q04      # one question directly
./gradlew quizAll  # all nine, in order
```

## Answer keys

Full versions with answers, explanations and expected output:
- 🇺🇦 Ukrainian — [keys/QUIZ.md](keys/QUIZ.md)
- 🇬🇧 English — [keys/QUIZ_EN.md](keys/QUIZ_EN.md)

---

# Quiz yourself

Nine questions, no answers. Check yourself against the [answer keys](#answer-keys) above.

## 1. When is the cat REALLY awake?

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

## 2. Dobby calls himself

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

## 3. The cat that stayed in the database

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

## 4. Nobody called `save()`

```java
@Transactional
public String report(Long id) {
    Cat cat = repo.findById(id).orElseThrow();
    cat.setName(cat.getName().toUpperCase());   // "just for the report"
    return "report for " + cat.getName();
}
```
There is no `repo.save(...)` anywhere. What is in the `name` column after the call?

- A) `dobby` — without `save()` nothing reaches the DB
- B) `DOBBY` — an `UPDATE` is flushed anyway
- C) `dobby`, and a `TransactionRequiredException` in the log
- D) `DOBBY`, but only with `ddl-auto=update`

**Follow-up:** which of these actually prevent the write?
`@Transactional(readOnly = true)` · a DTO projection · `entityManager.detach(cat)` ·
`@Transactional(propagation = SUPPORTS)` · `repo.flush()` at the end

## 5. Explain the failure: the toys are gone

```java
@Transactional(readOnly = true)
public Cat load(Long id) { return repo.findById(id).orElseThrow(); }

// somewhere in a controller / in main():
Cat cat = service.load(id);
System.out.println(cat.getToys().size());    // 💥
```
```
org.hibernate.LazyInitializationException: failed to lazily initialize
a collection of role: Cat.toys: could not initialize proxy - no Session
```
Why, and what fixes it **without** breaking the other 20 queries in the app?

- A) The cat was never saved — save it first
- B) The collection is a lazy proxy and the persistence context is already closed;
     fix with `JOIN FETCH` / `@EntityGraph` / a DTO for this one query
- C) Hibernate needs `fetch = EAGER` on every `@OneToMany`
- D) Turn `spring.jpa.open-in-view` back on and forget about it

## 6. One findAll(), four queries

Three cats, two toys each. The code calls `findAll()` once, the log shows **4** SQL statements.

```java
@Transactional(readOnly = true)
public int countToys() {
    int total = 0;
    for (Cat cat : repo.findAll()) {   // 1 select
        total += cat.getToys().size(); // ???
    }
    return total;
}
```

- A) Normal — Hibernate always sends one query per table
- B) N+1: `@EntityGraph` / `JOIN FETCH` / `@BatchSize` cuts it back to 1–2
- C) N+1: `fetch = EAGER` on `toys` cuts it back to 1
- D) The second-level cache is off; switch it on and the problem goes away

## 7. Explain the failure: I caught the exception, why is it still rolling back?

```java
@Transactional
public void register(String name) {
    repo.save(new Cat(name));
    try {
        audit.audit();            // @Transactional (REQUIRED), throws RuntimeException
    } catch (Exception e) {
        log.warn("audit failed, never mind");
    }
}
```
```
org.springframework.transaction.UnexpectedRollbackException:
Transaction silently rolled back because it has been marked as rollback-only
```
The exception was caught. Why does the commit still fail, and what is in the table?

- A) `Dobby` is saved, the exception is only a warning
- B) Nothing is saved: `audit()` joined the SAME transaction and marked it rollback-only;
     `catch` does not undo that mark
- C) Nothing is saved because `catch (Exception e)` also catches the rollback
- D) It is a deadlock between two connections

## 8. The cat needs the bowl, the bowl needs the cat

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
`@Lazy` on one constructor parameter · field injection · setter injection ·
`spring.main.allow-circular-references=true` · a third bean with the shared logic

## 9. Lifecycle order — bonus

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
