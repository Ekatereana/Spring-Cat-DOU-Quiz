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

## 1. П'ятниця, 17:00  (`./gradlew q01`)

Промо запустили дві години тому. `/checkout` працював цілий тиждень. Сьогодні нічого не деплоїли.

```java
@Transactional
public void checkout(String name) {
    repo.save(new Cat(name));
    paymentProvider.charge(name);   // зовнішній HTTP-виклик, ~1.5 с
}
```
```
java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available,
request timed out after 30000ms (total=10, active=10, idle=0, waiting=37)
```

- A) База лежить або вичерпано `max_connections`
- B) Hikari тече з'єднаннями
- C) Кожен checkout тримає з'єднання весь платіжний виклик; решта стає в чергу й падає по таймауту
- D) Пул замалий — `maximum-pool-size=200`

**Відповідь: C.** З'єднання береться на старті транзакції й повертається на коміті. Платіжний
виклик стоїть **усередині** транзакції, тож кожен запит пінить з'єднання на ~1.5 с, поки база
працює ~2 мс. З дефолтом Boot `maximum-pool-size=10` стеля — близько 6 checkout'ів за секунду;
усе зверху чекає в черзі пулу й падає після `connection-timeout` (дефолт 30 с). Код у п'ятницю
не міняли — змінився потік запитів.

**Що допомагає:** винести платіжний виклик за межі транзакції (1500 мс → ~2 мс) ·
read/connect timeout на HTTP-клієнті · `spring.jpa.open-in-view=false`.
**Що ні:** `maximum-pool-size=200` чи більше потоків Tomcat — черга просто переїде в базу,
а пул ніколи не має перевищувати `max_connections / кількість інстансів`.

**Вивід** (пул 5, таймаут 2 с, 12 покупців):
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

## 2. Коли кіт справді прокинувся?  (`./gradlew q02`)

Застосунок прогріває кеш в `ApplicationRunner`. Треба написати в discovery «сервіс піднявся» —
але тільки коли кеш теплий і можна приймати трафік.

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

- `ContextRefreshedEvent` — біни створені, більше нічого не гарантовано. Може стрельнути кілька
  разів (дочірні контексти, actuator `/refresh`) — слухач виконається повторно.
- `ApplicationStartedEvent` — контекст піднявся, але **runner'и ще не відпрацювали**: кеш холодний.
- `ApplicationReadyEvent` — остання подія: runner'и завершились, сервер приймає трафік. Сюди
  вішають прогрів, реєстрацію в discovery, старт консюмерів.
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

## 3. Доббі кличе сам себе  (`./gradlew q03`)

```java
public void feedTwice() { feedOnce(); }   // анотації немає, внутрішній виклик
@Transactional public void feedOnce() { ... }
```

- A) Так, нова транзакція
- B) Транзакції не буде взагалі
- C) Так, але тільки якщо `feedTwice()` теж `@Transactional`
- D) Помилка на старті

**Відповідь: B.** `@Transactional` працює через проксі (тут CGLIB — клас біна
`FeedingService$$SpringCGLIB$$0`). Проксі перехоплює лише виклики, які йдуть **через нього**.
`this.feedOnce()` не виходить за межі об'єкта, тож жоден інтерсептор не спрацьовує. Та сама
пастка для `@Cacheable`, `@Async`, `@Retryable`, `@PreAuthorize`.

**Як лікувати:** винести `feedOnce()` в окремий бін (найчистіше) · заінжектити бін сам у себе
(`@Lazy FeedingService self`) · `((FeedingService) AopContext.currentProxy()).feedOnce()` з
`@EnableAspectJAutoProxy(exposeProxy = true)` · справжній AspectJ weaving.

> 🐱 *І головне — не забудь мене погодувати!*

**Вивід:**
```
call through the proxy: service.feedOnce()
feedOnce()   tx active = true
call through the proxy, then internally: service.feedTwice()
feedTwice()  tx active = false
feedOnce()   tx active = false
```

---

## 4. Коту потрібна миска, мисці потрібен кіт  (`./gradlew q04`)

```java
@Service class CatService  { CatService(BowlService bowl) { } }
@Service class BowlService { BowlService(CatService cat)  { } }
```

- A) Стартує — Spring розрулює цикли триступеневим кешем
- B) Падає на старті: `BeanCurrentlyInCreationException`
- C) Стартує, падає на першому виклику
- D) Стартує з ворнінгом

**Відповідь: B.** Конструкторна ін'єкція вимагає готовий `BowlService`, щоб створити
`CatService`, і навпаки — нерозв'язно. Починаючи з Boot 2.6 циклічні залежності ще й заборонені
дефолтом (`spring.main.allow-circular-references=false`). Добра новина: падіння на старті, а не
о третій ночі в проді.

**Додаткове:** ✅ `@Lazy` на одному параметрі конструктора (інжектиться проксі, справжній бін
будується на першому виклику) · ✅ третій бін зі спільною логікою — єдиний фікс, що прибирає
проблему дизайну · ✅ `ApplicationEventPublisher` замість зворотного виклику ·
⚠️ field/setter ін'єкція — «працює» лише з `allow-circular-references=true` і лишає цикл на
місці разом із ризиком напівініціалізованого біна.

**Вивід:**
```
BeanCurrentlyInCreationException: Error creating bean with name 'BowlService':
Requested bean is currently in creation: Is there an unresolvable circular reference...
-- one side marked @Lazy --
bowl filled -> Dobby eats
```

---

## 5. Транзакція, яка зустріла світанок  (`./gradlew q05`)

Нічна джоба, 50 000 рядків, на кожен — виклик партнерського API. Хтось додав
`@Transactional(timeout = 20)`, «щоб точно не зависла». О 09:00 джоба ще біжить, з'єднання
відкрите з 01:00, і жодного `TransactionTimedOutException` у логах.

```java
@Transactional(timeout = 20)
public void nightRun() {
    for (Cat cat : repo.findAllDueForBilling()) {
        partnerApi.send(cat);      // socket timeout не налаштований
        cat.setBilled(true);
    }
}
```

- A) `timeout` працює тільки з JTA
- B) Це дедлайн, а не вотчдог — перевіряється, коли йде наступний JDBC-стейтмент
- C) Таймаут спрацював на 20 с, а те з'єднання — від іншої джоби
- D) Hikari `maxLifetime` мав його закрити

**Відповідь: B.** Spring кладе дедлайн у `ResourceHolder` транзакції й перевіряє його, коли
виконується наступний стейтмент, передаючи залишок секунд у `Statement.setQueryTimeout`.
Таймер-треду немає, потік застосунку ніхто не перериває. Тому цикл, який постійно ходить у базу,
падає на першому стейтменті після дедлайна, а цикл, що стоїть в HTTP-виклику, не виконує жодного
стейтмента — перевіряти нема кому, і з'єднання лишається забраним із пулу стільки, скільки триває
read сокета. `maxLifetime` теж не рятує: Hikari прибирає лише **простійні** з'єднання, не ті, що в роботі.

**Що обмежує шкоду:** connect/read timeout на кожному HTTP- і месенджер-клієнті (справжній фікс) ·
коротка транзакція на кожен чанк — з'єднання повертається в пул, а зроблене лишається зробленим ·
`idle_in_transaction_session_timeout` / `statement_timeout` на боці бази ·
Hikari `leakDetectionThreshold` заради рядка в лозі о 01:01.
**Що ні:** підняти `timeout` до 600 — той самий баг, тільки ніч довша.

**Вивід:**
```
-- loop keeps querying the database --
stopped after 2270 ms
TransactionTimedOutException: Transaction timed out: deadline was ...

-- loop waits 5 s for a third-party API --
   t+4s  pool active=1, idle=2        <- дедлайн 2 с, з'єднання все ще зайняте
stopped after 5010 ms
TransactionException: transaction timeout expired   <- аж на коміті, коли вже пізно

-- fix: slow call outside, one short transaction per chunk --
   t+1s  pool active=0, idle=3
rows committed: 5
```

---

## 6. Порядок життєвого циклу — бонус  (`./gradlew q06`)

**Відповідь: A — кіт догори дриґом.** Порядок ініціалізації одного біна:

1. конструктор (залежності вже заінжектені) — вуса
2. `@PostConstruct` — морда
3. `InitializingBean.afterPropertiesSet()` — вуха
4. (далі був би `@Bean(initMethod = ...)`)
5. `ApplicationRunner` / `CommandLineRunner` — хвіст
6. `ApplicationReadyEvent` — мурчання

`@PostConstruct` іде **раніше** за `afterPropertiesSet()`, бо анотаційні колбеки виконує
`CommonAnnotationBeanPostProcessor` у `postProcessBeforeInitialization`, а `afterPropertiesSet()`
— це вже сам крок ініціалізації. На зупинці все дзеркально:
`ContextClosedEvent` → `@PreDestroy` → `DisposableBean.destroy()`.

**Вивід:**
```
 > ^ <
( o.o )
 /\_/\
 ~~~~~
  purr
```
