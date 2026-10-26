# 🐱 Котячий квіз: що під капотом у Spring

```
     ______________________
    |  SPRING  DETECTIVE   |
    |______________________|
         \o   o/   ___
         /\_/\    (___)
        ( -.- )    | |
         > ^ <
```

Знайомтесь: **Сеньорй-Помідор Доббі**, кіт-консультант. Приходить, коли вже все горить,
нюхає логи, ставить кавусю і втішно каже: "Зара все пофіксимо"

Готові зазирнути під капот Spring, у цю палаючу безодню? Візуалізуйте, пятниця 17:00, ніхто нічого не деплоїв
(чесно-чесно), тільки включили checkout з-під feature flag (але воно ж протестоване).

Few moments later:

```
java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available,
request timed out after 30000ms (total=10, active=10, idle=0, waiting=37)
```
є гіпотези? Тоді го розбиратися -->

П’ять мізансцен, код, лайв приклади, і лише одна правильна відповідь

P.s пруфи можете глянути тут https://github.com/Ekatereana/Spring-Cat-DOU-Quiz, код там позапускати наприклад.
І так, найцікавіше — в кінці, на солоденьке.

> Для студентів: приберіть блоки з відповідями.

---

## Справа №1. П'ятниця, 17:00  (`./gradlew q01`)

Тож візуалізуйте: пʼятниця, вечір, 17:00, ніхто нічого не деплоїть... запланований ролаут нової
фічі вмикають feature flag-ом (згодна, ну хто це робить у пʼятницю). Промо живе дві години,
`/checkout` працював цілий тиждень, сьогодні ніхто нічого не деплоїв.

```java
@Transactional
public void checkout(String name) {
    repo.save(new Cat(name));
    paymentProvider.charge(name);   // зовнішній HTTP, ~1.5 с
}
```
```
java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available,
request timed out after 30000ms (total=10, active=10, idle=0, waiting=37)
```
Що сталося?

- A) База лежить або вичерпано `max_connections`
- B) Hikari тече з'єднаннями — рестарт і поїхали
- C) Кожен checkout тримає конекшн увесь платіжний виклик; решта стає в чергу й падає по таймауту
- D) Пул замалий — `maximum-pool-size=200` і по домам

**Правильно: C.** Конекшн беруть на старті транзакції й віддають на коміті. Платіж стоїть
**усередині** — кожен запит пінить конекшн ~1.5 с, поки база працює ~2 мс. З дефолтом Boot
`maximum-pool-size=10` стеля ~6 checkout/с, решта чекає в черзі пулу й падає через
`connection-timeout` (30 с). Код у пʼятницю не міняли — змінився потік запитів.

**Що робить?:** платіж за межі транзакції (1500 мс → ~2 мс) · connect/read timeout на HTTP-клієнті ·
`spring.jpa.open-in-view=false`.
**Що не варто робить:** `maximum-pool-size=200` чи більше потоків Tomcat — черга просто переїде в базу.
Пул ніколи не більший за `max_connections / кількість інстансів`.

---

## Справа №2. Дорефакторилися до ручки  (`./gradlew q02`)

**Для розгону (а раптом ви забули)** — що там Spring на старті запускає
`ApplicationStartingEvent` → `ApplicationEnvironmentPreparedEvent` →
`ApplicationContextInitializedEvent` → `ApplicationPreparedEvent` →
`ContextRefreshedEvent` → `ApplicationStartedEvent` → *runners* → `ApplicationReadyEvent`.
Порт, до речі, сервер починає слухати ще до `ContextRefreshedEvent` — у `finishRefresh()`.

**Кейс:** прогрів кешу жив в `ApplicationRunner`, реєстрація в discovery — на
`ApplicationReadyEvent`. На ревʼю сказали «це ж обидва про старт, нащо два механізми».

**Було:**
```java
@Bean ApplicationRunner warmUp(Cache cache) {
    return args -> cache.loadTopCats();          // ~20 с
}

@EventListener(ApplicationReadyEvent.class)
void publishUp() { discovery.register(); }
```
**Стало:**
```java
@EventListener(ApplicationReadyEvent.class)
void warmUpCache() { cache.loadTopCats(); }      // ~20 с

@EventListener(ApplicationReadyEvent.class)
void publishUp()   { discovery.register(); }
```

Красиво так? Прод не сильно згоден. Перші 20 секунд після кожного деплою — p95 у стелю й промахи кеша.

Подію не міняли — вона й була правильна. То що зламалось?

- A) `ApplicationReadyEvent` надто рано — треба `AvailabilityChangeEvent`
- B) Між двома слухачами **однієї** події порядку не гарантовано: `publishUp()` законно
  виконається першим, і discovery отримає «готовий» з холодним кешем
- C) Прогрів блокує публікацію — вішаємо `@Async` і хай собі гріється
- D) Spring бере лише перший `@EventListener` на подію в біні

**Правильно: B.** Рефакторинг прибрав гарантію порядку й нічим її не замінив.

- Слухачів однієї події збирає рефлексія; `AnnotationAwareOrderComparator` сортує тільки те,
  що має `@Order`. Решта — як віддав JVM. Працювало випадково.
- Runner'и ж упорядковані щодо події: `run()` кличе `callRunners()` і тільки потім публікує
  `ApplicationReadyEvent`.
- `@Async` (C) робить гірше: реєстрація обганяє прогрів **завжди**.
- A — неправда: порт відкритий ще з `finishRefresh()`. D — неправда: кілька слухачів на подію
  в одному класі норм.

**А як красиво, якщо вже дуже хочеться на подіях?** Порядок треба попросити явно:

```java
@Order(1)
@EventListener(ApplicationReadyEvent.class)
void warmUpCache() { cache.loadTopCats(); }

@Order(2)                                      // реєстрація — завжди останньою
@EventListener(ApplicationReadyEvent.class)
void publishUp()   { discovery.register(); }
```

Працює, але це домовленість, яку треба памʼятати на кожному новому слухачі: забув `@Order` —
і новий слухач став у чергу випадково. Runner дає те саме безплатно й без домовленостей.

**Що робить?:** прогрів назад в runner (найдешевше) · `@Order` на **всіх** слухачах цієї події,
реєстрація останньою · `AvailabilityChangeEvent` + `ReadinessState` і readiness probe,
якщо по-кубернетівськи.

---

## Справа №3. Прогрів, який не дожив до трафіку  (`./gradlew q02`)

**Кейс:** прогрів кешу таки залишили в слухачі — «все про старт тримаємо в подіях».
Сьогодні `cat-service` віддає 503, і `loadTopCats()` кидає `IllegalStateException`.

```java
@EventListener(ApplicationReadyEvent.class)
void warmUpCache() { cache.loadTopCats(); }    // boom

@EventListener(ApplicationReadyEvent.class)
void publishUp()   { discovery.register(); }
```

Що станеться із застосунком?

- A) Виняток залогується, `publishUp()` однаково виконається
- B) Решта слухачів цієї події пропускається, `run()` кидає виняток далі, контекст
  закривається, процес виходить з ненульовим кодом
- C) Контекст уже піднятий — застосунок житиме, просто без кеша
- D) Spring перепублікує подію

**Правильно: B.** Слухач виконується синхронно, у потоці публікатора, тож виняток летить назад.
`ApplicationReadyEvent` публікує сам `SpringApplication.run()`, а він не ловить мʼяко:
`handleRunFailure()` закриває контекст і кидає далі. `publishUp()` не виконається взагалі.

```
 біни ──▶ порт слухає ──▶ ContextRefreshed ──▶ Started ──▶ runners ──▶ Ready ──▶ живемо
   1            2               2                  3           4         5
```

| # | Виняток тут | Що буде |
|---|---|---|
| 1 | конструктор, `@PostConstruct`, `afterPropertiesSet()` | refresh падає, порт навіть не відкрився — найчесніше падіння |
| 2 | слухач `ContextRefreshedEvent` | падає в `refresh()`, але порт уже слухає — вікно для трафіку |
| 3 | слухач `ApplicationStartedEvent` | те саме, і кеш холодний: runner'и не починались |
| 4 | runner | `callRunners()` падає, `ApplicationReadyEvent` не публікується зовсім |
| 5 | слухач `ApplicationReadyEvent` | наш випадок: решту слухачів скіпнуто, контекст закрито, exit code ≠ 0 |
| — | будь-який `@Async` слухач | виняток ковтає `AsyncUncaughtExceptionHandler`: под «готовий» і зламаний |

---

## Справа №4. Доббі кличе сам себе  (`./gradlew q03`)

```java
public void feedTwice() { feedOnce(); }   // анотації немає, внутрішній виклик
@Transactional public void feedOnce() { ... }
```

Викликаємо `service.feedTwice()` зовні. Чи буде транзакція всередині `feedOnce()`?

- A) Так, нова транзакція
- B) Транзакції не буде взагалі
- C) Так, але тільки якщо `feedTwice()` теж `@Transactional`
- D) Помилка на старті: `@Transactional` на методі, який кличуть зсередини

**Правильно: B.** `@Transactional` працює через проксі (тут CGLIB, клас біна
`FeedingService$$SpringCGLIB$$0`). `this.feedOnce()` не виходить за межі обʼєкта — жоден
інтерсептор не спрацює. Та сама пастка для `@Cacheable`, `@Async`, `@Retryable`, `@PreAuthorize`.

**Що робить?:** винести `feedOnce()` в окремий бін (найчистіше) · заінжектити бін сам у себе
(`@Lazy FeedingService self`) · `((FeedingService) AopContext.currentProxy()).feedOnce()`
з `@EnableAspectJAutoProxy(exposeProxy = true)` · справжній AspectJ weaving.

> 🐱 *І головне — не забудь мене погодувати.*

---

## Справа №5. Транзакція, яка зустріла світанок  (`./gradlew q05`)

**Тікет:** нічна джоба, 50 000 рядків, на кожен — виклик партнерського API. Хтось додав
`@Transactional(timeout = 20)`, «щоб точно не зависла». О 09:00 джоба ще біжить, конекшн
відкритий з 01:00, і жодного `TransactionTimedOutException` у логах.

```java
@Transactional(timeout = 20)
public void nightRun() {
    for (Cat cat : repo.findAllDueForBilling()) {
        partnerApi.send(cat);      // socket timeout не налаштований
        cat.setBilled(true);
    }
}
```

- A) `timeout` працює тільки з JTA, а в нас же простий JDBC
- B) Це дедлайн, а не вотчдог — перевіряється, коли йде наступний JDBC-стейтмент
- C) Таймаут спрацював на 20 с, а той конекшн — від іншої джоби
- D) Hikari `maxLifetime` мав його закрити

**Правильно: B.** Spring кладе дедлайн у `ResourceHolder` і перевіряє його на наступному
стейтменті, передаючи залишок у `Statement.setQueryTimeout`. Таймер-треду немає, потік ніхто
не перериває. Цикл, що ходить у базу, падає на першому стейтменті після дедлайна; цикл, що стоїть
в HTTP-виклику, не виконує стейтментів — перевіряти нема кому. `maxLifetime` теж не рятує:
Hikari прибирає тільки **простійні** конекшни.

**Що робить?:** connect/read timeout на кожному HTTP- і месенджер-клієнті (справжній фікс) ·
коротка транзакція на чанк · `idle_in_transaction_session_timeout` / `statement_timeout`
на боці бази · `leakDetectionThreshold` заради рядка в лозі о 01:01.
**Що не варто робить:** підняти `timeout` до 600 — той самий баг, тільки ніч довша.

---

## Бонус. Із чого складається кіт  (`./gradlew q06`)

Кожен колбек життєвого циклу друкує частину кота. Який кіт вийде?

```java
@Component
class CatBean implements InitializingBean {

    CatBean()                        { System.out.println(" > ^ < "); }  // вуса
    @PostConstruct void post()       { System.out.println("( o.o )"); }  // морда
    public void afterPropertiesSet() { System.out.println(" /\_/\ "); }  // вуха
    @EventListener(ApplicationReadyEvent.class)
    void ready()                     { System.out.println("  purr  "); } // мурчання
}

@Bean ApplicationRunner runner() {
    return args -> System.out.println(" ~~~~~ ");                        // хвіст
}
```
```
A) догори дриґом    B) нормальний кіт   C) мурчить першим   D) хвіст останній
 > ^ <               /\_/\                purr               /\_/\
( o.o )             ( o.o )              /\_/\              ( o.o )
 /\_/\               > ^ <              ( o.o )              > ^ <
 ~~~~~               ~~~~~               > ^ <                purr
  purr                purr               ~~~~~               ~~~~~
```

**Правильно: A — кіт догори дриґом.** Порядок для одного біна:

1. конструктор (залежності вже заінжектені) — вуса
2. `@PostConstruct` — морда
3. `InitializingBean.afterPropertiesSet()` — вуха
4. (далі був би `@Bean(initMethod = ...)`)
5. `ApplicationRunner` / `CommandLineRunner` — хвіст
6. `ApplicationReadyEvent` — мурчання

`@PostConstruct` іде раніше за `afterPropertiesSet()`, бо анотаційні колбеки виконує
`CommonAnnotationBeanPostProcessor` у `postProcessBeforeInitialization`, а `afterPropertiesSet()`
— це вже сам крок ініціалізації. На зупинці все дзеркально:
`ContextClosedEvent` → `@PreDestroy` → `DisposableBean.destroy()`.

---

Справи закриті, кавуся допита. Доббі пішов їсти — він своє зробив.
