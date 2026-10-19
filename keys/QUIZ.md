# 🐱 Квіз «Кіт Доббі і Spring Essentials» — з відповідями

```
    _______________
   |  SPRING  КВІЗ |
   |_______________|
       \o   o/
       /\_/\
      ( o.o )
       > ^ <
```

> Щоб роздати студентам: прибрати блоки **Відповідь** і **Вивід**.
> До кожного питання є робочий застосунок: `./gradlew qNN`.

---

## 1. Коли кіт справді прокинувся?  (`./gradlew q01`)

Застосунок Доббі прогріває кеш в `ApplicationRunner`. Треба написати в discovery «сервіс
піднявся» — але тільки тоді, коли кеш теплий і можна приймати трафік.

```java
@EventListener(???.class)
void publishUp() { discovery.register(); }
```

- A) `ContextRefreshedEvent`
- B) `ApplicationStartedEvent`
- C) `ApplicationReadyEvent`
- D) `ApplicationContextInitializedEvent`

**Відповідь: C.** Порядок подій на старті:
`ApplicationStartingEvent` → `ApplicationEnvironmentPreparedEvent` →
`ApplicationContextInitializedEvent` → `ApplicationPreparedEvent` →
`ContextRefreshedEvent` → `ApplicationStartedEvent` → *runners* → `ApplicationReadyEvent`.

- `ContextRefreshedEvent` — біни створені, більше нічого не гарантовано. Може стрельнути
  кілька разів (дочірні контексти, actuator `/refresh`) — слухач виконається повторно.
- `ApplicationStartedEvent` — контекст піднявся, але **runner'и ще не відпрацювали**: кеш холодний.
- `ApplicationReadyEvent` — остання подія: runner'и завершились, сервер приймає трафік.
  Сюди вішають прогрів, реєстрацію в discovery, старт консюмерів.
- `AvailabilityChangeEvent` (`ReadinessState.ACCEPTING_TRAFFIC`) — те саме «по-кубернетівськи»,
  і воно вміє перемикатись назад у `REFUSING_TRAFFIC`.

**Вивід:**
```
[event]   ContextRefreshedEvent   (beans created, context refreshed)
[event]   ApplicationStartedEvent (context up, runners NOT run yet)
[runner]  cache warmed up
[event]   ApplicationReadyEvent   (runners finished, app can serve traffic)
```

---

## 2. Доббі кличе сам себе  (`./gradlew q02`)

```java
@Service
class FeedingService {

    public void feedTwice() {          // анотації немає
        feedOnce();                    // this.feedOnce()
    }

    @Transactional
    public void feedOnce() { ... }     // анотація тут
}
```
Ззовні викликають `service.feedTwice()`. Чи буде транзакція всередині `feedOnce()`?

- A) Так, нова — анотація ж на методі, який виконується
- B) Транзакції не буде взагалі
- C) Так, але тільки якщо `feedTwice()` теж `@Transactional`
- D) Помилка на старті

**Відповідь: B.** `@Transactional` працює через проксі (тут CGLIB — клас біна
`FeedingService$$SpringCGLIB$$0`). Проксі перехоплює тільки виклики, які йдуть **через нього**.
`this.feedOnce()` не виходить за межі об'єкта, тому жоден інтерсептор не спрацьовує.
Та сама пастка для `@Cacheable`, `@Async`, `@Retryable`, `@PreAuthorize`.

**Як лікувати:** винести `feedOnce()` в окремий бін (найчистіше) · заінжектити бін сам у себе
(`@Lazy FeedingService self`) · `((FeedingService) AopContext.currentProxy()).feedOnce()` з
`@EnableAspectJAutoProxy(exposeProxy = true)` · справжній AspectJ weaving.

**Вивід:**
```
call through the proxy: service.feedOnce()
feedOnce()   tx active = true
call through the proxy, then internally: service.feedTwice()
feedTwice()  tx active = false
feedOnce()   tx active = false
```

---

## 3. Кіт, який лишився в базі  (`./gradlew q03`)

```java
class NoFoodException extends Exception { }      // дивимось на батьківський клас

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
Викликали обидва методи, обидва кинули виняток, обидва винятки спіймані ззовні.
Які коти лишились у таблиці?

- A) Жодного — виняток завжди відкочує транзакцію
- B) Тільки `Dobby`
- C) Тільки `Shadow`
- D) Обидва

**Як відрізнити просто зі сніпета:** `NoFoodException extends Exception` напряму — отже
**checked**, і саме тому в сигнатурі стоїть `throws NoFoodException`. `IllegalStateException`
лежить під `RuntimeException` — **unchecked**, `throws` не потрібен. Ієрархія: усе під
`Exception` — checked, **крім** гілки `RuntimeException`; `Error` теж unchecked.

**Відповідь: B.** Дефолтне правило Spring: відкат на `RuntimeException` **або** `Error`.
Checked виняток вважається бізнес-результатом, і транзакція **комітиться**. (У JTA/EJB
поведінка така сама — це дефолт Spring, а не правило JPA.)

**Фікс:** `@Transactional(rollbackFor = Exception.class)` або кидати unchecked.
Є ще `rollbackForClassName` і централізований `TransactionAttributeSource`.

**Вивід:**
```
rows after a CHECKED exception:   1   <- committed!
rows after an UNCHECKED exception: 0  <- rolled back
rows with rollbackFor = Exception.class: 0  <- rolled back
```

---

## 4. Ніхто не кликав `save()`  (`./gradlew q04`)

```java
@Transactional
public String report(Long id) {
    Cat cat = repo.findById(id).orElseThrow();
    cat.setName(cat.getName().toUpperCase());   // «тільки для звіту»
    return "report for " + cat.getName();
}
```
`repo.save(...)` немає ніде. Що в колонці `name` після виклику?

- A) `dobby` — без `save()` у базу нічого не поїде
- B) `DOBBY` — `UPDATE` однаково полетить
- C) `dobby` і `TransactionRequiredException` у логах
- D) `DOBBY`, але тільки з `ddl-auto=update`

**Відповідь: B — `DOBBY`.** Усередині транзакції сутність із `findById` — **managed**.
На flush (перед комітом або перед запитом по тій самій таблиці) Hibernate порівнює її зі
знімком, зробленим при завантаженні, і генерує `UPDATE` на кожне змінене поле. Dirty checking.
`save()` потрібен лише для нових сутностей; для managed це no-op.

Це і є «випадковий запис»: маппер або логування міняють сутність — і рядок у проді змінюється.

**Додаткове — що реально рятує:**
- ✅ `@Transactional(readOnly = true)` — Hibernate ставить сесії `FlushMode.MANUAL` і не робить
  dirty checking (плюс запит може піти на репліку)
- ✅ DTO-проєкція — managed-сутності просто немає в руках (найкращий варіант)
- ✅ `entityManager.detach(cat)` — працює, але легко забути
- ❌ `propagation = SUPPORTS` — якщо транзакція вже є, flush усе одно станеться
- ❌ `repo.flush()` — це навпаки примусовий запис

**Вивід (`show-sql`):**
```
select c1_0.id,c1_0.name from cat c1_0 where c1_0.id=?
update cat set name=? where id=?          <- цього ніхто не просив
name in DB now: DOBBY
-- той самий код під readOnly = true --
select c1_0.id,c1_0.name from cat c1_0 where c1_0.id=?
name in DB now: shadow
```

---

## 5. Поясни поломку: іграшки зникли  (`./gradlew q05`)

```java
@Transactional(readOnly = true)
public Cat load(Long id) { return repo.findById(id).orElseThrow(); }

Cat cat = service.load(id);
System.out.println(cat.getToys().size());    // 💥
```
```
org.hibernate.LazyInitializationException: failed to lazily initialize
a collection of role: Cat.toys: could not initialize proxy - no Session
```

- A) Кота не зберегли — спершу `save()`
- B) Колекція — лінива проксі, а persistence context уже закрито; лікується
     `JOIN FETCH` / `@EntityGraph` / DTO для цього конкретного запиту
- C) Треба `fetch = EAGER` на кожному `@OneToMany`
- D) Увімкнути назад `spring.jpa.open-in-view` і забити

**Відповідь: B.** `cat.toys` — ліниве `PersistentBag`. Транзакція (а з нею persistence context
і з'єднання) закрилась на виході з `load()`, тож ініціалізувати проксі нема звідки.

**Точкові фікси:** `@Query("select c from Cat c join fetch c.toys where c.id = :id")` ·
`@EntityGraph(attributePaths = "toys")` · DTO/інтерфейс-проєкція · зробити роботу всередині
транзакції і повертати готовий результат, а не сутність.

**Чому решта — погано:**
- `fetch = EAGER` лагодить цей виклик і псує всі інші запити, що тягнуть `Cat`
- `spring.jpa.open-in-view = true` (дефолт Boot і той самий ворнінг на старті) ховає баг:
  сесія живе весь HTTP-запит, ліниві підвантаження стріляють із в'ю-шару, а з'єднання
  тримається поза пулом до кінця відповіді

**Вивід:**
```
cat loaded fine: Dobby
LazyInitializationException: failed to lazily initialize a collection of role:
quiz.q05.Cat.toys: could not initialize proxy - no Session
with 'join fetch c.toys': toys = 2
```

---

## 6. Один findAll(), чотири запити  (`./gradlew q06`)

Три коти, по дві іграшки. У коді один `findAll()`, у логах **4** SQL.

- A) Нормально — Hibernate завжди робить один запит на таблицю
- B) N+1: `@EntityGraph` / `JOIN FETCH` / `@BatchSize` зводять до 1–2 запитів
- C) N+1: `fetch = EAGER` на `toys` зведе до 1
- D) Вимкнений кеш другого рівня, увімкнути — і проблеми нема

**Відповідь: B.** Класичний N+1: 1 select на котів і ще по одному на кожного кота, щоб
ініціалізувати `toys` (3 коти → 4 звернення). На 1000 котів це 1001 похід у базу.

**Фікси:**
- `@EntityGraph(attributePaths = "toys")` або `JOIN FETCH` → 1 запит
- `@BatchSize(size = 50)` / `hibernate.default_batch_fetch_size` → 1 + N/50; єдине, що
  масштабується, коли колекцій кілька
- DTO-проєкція з агрегатом (`select c.id, count(t)`), якщо треба тільки число
- ⚠️ `JOIN FETCH` одразу по **двох** колекціях дає декартів добуток — тоді batch size
  або `@EntityGraph` + `distinct`
- ❌ `fetch = EAGER` N+1 не прибирає: `findAll()` однаково тягне колекцію на кожен рядок.
  Проблема просто переїжджає і додається до всіх інших запитів.

**Вивід:**
```
findAll() + cat.toys.size():   toys = 6, SQL statements = 4   (1 + N)
@EntityGraph("toys"):          toys = 6, SQL statements = 1
```

---

## 7. Поясни поломку: я ж спіймав виняток  (`./gradlew q07`)

```java
@Transactional
public void register(String name) {
    repo.save(new Cat(name));
    try {
        audit.audit();            // @Transactional (REQUIRED), кидає RuntimeException
    } catch (Exception e) {
        log.warn("audit failed, never mind");
    }
}
```
```
org.springframework.transaction.UnexpectedRollbackException:
Transaction silently rolled back because it has been marked as rollback-only
```

- A) `Dobby` збережений, виняток — просто ворнінг
- B) Не збережено нічого: `audit()` приєднався до ТІЄЇ САМОЇ транзакції і позначив її
     rollback-only; `catch` цю позначку не знімає
- C) Нічого не збережено, бо `catch (Exception e)` ловить і сам rollback
- D) Це дедлок між двома з'єднаннями

**Відповідь: B.** `audit()` має `@Transactional` з дефолтним `Propagation.REQUIRED`, тобто
**приєднується** до транзакції викликача, а не відкриває свою. Коли з нього вилітає
`RuntimeException`, інтерсептор ставить спільній транзакції `rollbackOnly`. `catch` у
викликача прапорець не знімає — на коміті менеджер бачить `rollbackOnly` і кидає
`UnexpectedRollbackException`. Відкочується все, разом із котом.

**Фікси:**
- `Propagation.REQUIRES_NEW` для необов'язкової роботи — власна транзакція і власне з'єднання
  (стежити за пулом: два з'єднання одночасно)
- `@Transactional(noRollbackFor = AuditException.class)`, якщо падіння справді нешкідливе
- не викликати це всередині транзакції взагалі: публікувати подію і ловити
  `@TransactionalEventListener(phase = AFTER_COMMIT)`
- якщо внутрішній метод — просто хелпер, зняти з нього `@Transactional`

**Вивід:**
```
caught and ignored: audit backend is down
UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only
cats in DB: 0
-- audit moved to REQUIRES_NEW --
cats in DB: 1
```

---

## 8. Коту потрібна миска, мисці потрібен кіт  (`./gradlew q08`)

```java
@Service class CatService  { CatService(BowlService bowl) { } }
@Service class BowlService { BowlService(CatService cat)  { } }
```

- A) Стартує — Spring розрулює цикли триступеневим кешем
- B) Падає на старті: `BeanCurrentlyInCreationException`
- C) Стартує, падає на першому виклику
- D) Стартує з ворнінгом

**Відповідь: B.** З конструкторною ін'єкцією Spring має добудувати `BowlService` до того, як
створить `CatService`, і навпаки — нерозв'язно: `BeanCurrentlyInCreationException`. Починаючи
з Boot 2.6 циклічні залежності заборонені дефолтом (`spring.main.allow-circular-references=false`).
Застосунок не стартує — і це добра новина: падіння на старті, а не о третій ночі в проді.

**Додаткове:**
- ✅ `@Lazy` на одному параметрі конструктора — інжектиться проксі, справжній бін будується
  на першому виклику
- ✅ третій бін зі спільною логікою — єдиний фікс, що прибирає проблему дизайну
- ✅ `ApplicationEventPublisher` замість зворотного виклику
- ⚠️ field/setter ін'єкція — «працює» тільки з `spring.main.allow-circular-references=true`;
  залежність доставляється після конструктора, тож цикл лишається разом із ризиком
  напівініціалізованого біна

**Вивід:**
```
BeanCurrentlyInCreationException: Error creating bean with name 'BowlService':
Requested bean is currently in creation: Is there an unresolvable circular reference...
-- one side marked @Lazy --
bowl filled -> Dobby eats
```

---

## 9. Порядок життєвого циклу — бонус  (`./gradlew q09`)

**Відповідь: A — кіт догори дриґом.** Порядок ініціалізації одного біна:

1. конструктор (залежності вже заінжектені) — вуса
2. `@PostConstruct` — морда
3. `InitializingBean.afterPropertiesSet()` — вуха
4. (далі був би `@Bean(initMethod = ...)`)
5. `ApplicationRunner` / `CommandLineRunner` — хвіст
6. `ApplicationReadyEvent` — мурчання

`@PostConstruct` іде **раніше** за `afterPropertiesSet()`, бо анотаційні колбеки виконує
`CommonAnnotationBeanPostProcessor` у `postProcessBeforeInitialization`, а
`afterPropertiesSet()` — це вже сам крок ініціалізації. На зупинці все дзеркально:
`ContextClosedEvent` → `@PreDestroy` → `DisposableBean.destroy()`.

**Вивід:**
```
 > ^ <
( o.o )
 /\_/\
 ~~~~~
  purr
```
