# 🐱 Cat quiz #2: data, Hibernate and config

```
     ______________________
    |  SPRING  DETECTIVE   |
    |______________________|
         \o   o/   ___
         /\_/\    (___)
        ( -.- )    | |
         > ^ <
```

Dobby finished his coffee and opened the second folder: nothing crashes loudly in here — here
the data quietly changes itself and features quietly do not work.

Five scenes, code, live samples, and exactly one right answer.

P.s. the proofs live here https://github.com/Ekatereana/Spring-Cat-DOU-Quiz — run the code yourself

> For students: strip the answer blocks.

---

## Case #1. The migration that saved nothing  (`./gradlew q07`)

**The ticket:** before the real data migration we did a dry run on prod: read every row,
normalise the names in memory, count how many rows it would touch. No `save()`, no `flush()`.
We looked at the report, rolled the release back, went to bed. Next day: validation errors on
names nobody had changed.

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

**Right answer: B.** Everything the repository returned **inside the transaction** is a *managed*
entity. Hibernate takes a snapshot at load time and on commit does an auto-flush plus **dirty
checking**: whatever differs gets updated. `save()` is only needed for **new** objects.

```
 @Transactional ─▶ findAll() ─▶ MANAGED + snapshot
                                     │
                   cat.name = "dobby" │  (nobody called save())
                                     ▼
             commit ─▶ auto-flush ─▶ dirty checking ─▶ UPDATE cat SET name=? WHERE id=?
```

"But I never committed" does not save you either: in `FlushMode.AUTO` Hibernate also flushes
**before a query** that may touch the modified tables — the `UPDATE` can fly mid-method.

And the release rollback did not help because the transaction **completed successfully**: there
was nothing to roll back. A rollback brings the code back, not the rows.

| What we do | Saves us? | Why |
|---|---|---|
| `@Transactional(readOnly = true)` | ✅ | the session goes to `FlushMode.MANUAL` — no auto-flush, and no snapshots either |
| a projection or DTO: `select c.name from Cat c` | ✅ | no managed entities at all — nothing to dirty-check |
| `entityManager.detach(cat)` before the change | ✅ | the entity leaves the persistence context |
| "just don't call `save()`" / rolling the release back | ❌ | that is exactly how we got here |

A caveat: `readOnly = true` does not block an explicit `flush()`. Spring also sets
`Connection.setReadOnly(true)` — Postgres will refuse writes at the database level, while H2 and
MySQL quietly ignore the flag.

---

## Case #2. The profile that never was  (`./gradlew q08`)

**The ticket:** locally and on staging everything flies. On CI, with
`spring.profiles.active=test`, the application does not start at all. The mock was added "so the
tests don't hit the real feeder".

```java
@Bean Feeder realFeeder() { return new RealFeeder(); }   // the default, no profile

@Profile("test")
@Bean Feeder mockFeeder() { return new MockFeeder(); }   // the mock for tests

@Bean CatService catService(Feeder feeder) { ... }
```

What happens on CI?

- A) Spring picks the profiled bean — a profile is more specific than a default
- B) `NoUniqueBeanDefinitionException` at startup: a bean without `@Profile` is active **always**,
  so there are two candidates
- C) `mockFeeder` overrides `realFeeder` — bean overriding
- D) Nothing: `@Profile("test")` is simply ignored outside tests

**Right answer: B.** No `@Profile` does not mean "default", it means "active in every profile".
With `test` both definitions are alive, and the injection point sees two candidates of the same
type. C would be true only for beans with the same **name** — that would be a
`BeanDefinitionOverrideException`, because overriding is disabled since Boot 2.1.

**What to do:** `@Profile("!test")` on the default · or `@ConditionalOnMissingBean` on the mock ·
`@Primary`/`@Qualifier` if you need both · expressions work too: `@Profile("prod & !legacy")`.

---

## Case #3. The feature nobody switched on  (`./gradlew q08`)

**The case:** the nightly billing job is hidden behind the `prod` profile, and the scheduler
collects jobs as a collection. On dev nobody activates that profile.

```java
@Profile("prod")
@Bean NightJob billingJob() { ... }

@Bean Scheduler scheduler(List<NightJob> jobs) { ... }   // collection injection
```

We start without `prod`. What happens?

- A) `NoSuchBeanDefinitionException` at startup
- B) Starts fine, with **0 jobs** in the list — billing simply does not exist, and nobody will
  tell you
- C) Spring injects an empty proxy instead of the bean
- D) It blows up the first time the scheduler runs

**Right answer: B.** A collection injection point accepts zero candidates as a perfectly legal
empty list. A single `NightJob` would have failed with `NoSuchBeanDefinitionException` — but
`List<NightJob>` starts up all green. The feature is quietly off, health checks are happy,
invoices are not being issued.

**What to do:** `Assert.notEmpty(jobs, ...)` or a check in `@PostConstruct` · log how many jobs
were picked up at startup · for a feature that must be visible, `@ConditionalOnProperty` plus an
explicit health indicator beats a profile.

---

## Case #4.

_(free slot)_

---

## Case #5.

_(free slot)_

---

Cases closed, coffee finished. Dobby went to eat — he did his part.
