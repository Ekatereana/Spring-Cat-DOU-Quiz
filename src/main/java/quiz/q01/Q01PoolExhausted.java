// 1. Friday, 17:00: HikariPool-1 - Connection is not available
// Run: ./gradlew q01
package quiz.q01;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import quiz.common.Cats;

@SpringBootApplication(scanBasePackages = "quiz.q01")
public class Q01PoolExhausted {

    private static final int POOL_SIZE = 5;      // production default is 10
    private static final int SHOPPERS = 12;      // Friday traffic

    public static void main(String[] args) {
        Cats.cat("Friday 17:00, twelve cats at the checkout, five connections");

        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Q01PoolExhausted.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .properties("spring.datasource.hikari.maximum-pool-size=" + POOL_SIZE,
                            "spring.datasource.hikari.connection-timeout=2000")
                .run(args)) {

            CheckoutService service = ctx.getBean(CheckoutService.class);
            CatRepo repo = ctx.getBean(CatRepo.class);
            HikariPoolMXBean pool = ctx.getBean(HikariDataSource.class).getHikariPoolMXBean();

            System.out.println("   pool size = " + POOL_SIZE
                    + ", connection-timeout = 2000 ms, payment call = 1500 ms, shoppers = " + SHOPPERS + "\n");

            System.out.println("   -- payment call INSIDE the transaction --");
            repo.deleteAll();
            run(service::checkout, pool);
            System.out.println("   orders written: " + Cats.RED + repo.count() + Cats.R + " of " + SHOPPERS);

            System.out.println("\n   -- payment call moved OUT of the transaction --");
            repo.deleteAll();
            run(service::checkoutFixed, pool);
            System.out.println("   orders written: " + Cats.G + repo.count() + Cats.R + " of " + SHOPPERS);

            Cats.answer("The pool was never the bottleneck - the transaction was. A connection is "
                    + "borrowed for the whole @Transactional method, so a 1.5 s payment call pins it "
                    + "for 1.5 s. 5 connections / 1.5 s = ~3 checkouts per second, everybody else waits "
                    + "and dies on connection-timeout.");
            System.out.println("   Fixes: never call an external service inside a transaction; keep transactions short; "
                    + "set a timeout on the HTTP client; size the pool deliberately "
                    + "(a pool bigger than the DB max_connections only moves the queue); "
                    + "turn spring.jpa.open-in-view off so the connection is not held for the whole request.");
        }
    }

    private static void run(Consumer<String> checkout, HikariPoolMXBean pool) {
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        String firstError = null;

        try (ExecutorService pooledThreads = Executors.newFixedThreadPool(SHOPPERS)) {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 1; i <= SHOPPERS; i++) {
                String name = "cat-" + i;
                tasks.add(() -> {
                    try {
                        checkout.accept(name);
                        ok.incrementAndGet();
                        return null;
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        Throwable root = e;
                        while (root.getCause() != null) { root = root.getCause(); }
                        return root.getClass().getSimpleName() + ": " + root.getMessage();
                    }
                });
            }
            long started = System.currentTimeMillis();
            new Thread(() -> peek(pool)).start();
            for (Future<String> f : pooledThreads.invokeAll(tasks)) {
                String error = f.get();
                if (error != null && firstError == null) { firstError = error; }
            }
            System.out.println("   finished in " + (System.currentTimeMillis() - started) + " ms: "
                    + Cats.G + ok.get() + " ok" + Cats.R + ", "
                    + (failed.get() == 0 ? Cats.G : Cats.RED) + failed.get() + " failed" + Cats.R);
        } catch (Exception e) {
            Cats.boom(String.valueOf(e));
        }
        if (firstError != null) { Cats.boom(firstError); }
    }

    /** Snapshot of the pool while the stampede is running. */
    private static void peek(HikariPoolMXBean pool) {
        try {
            Thread.sleep(700);
            System.out.println("   pool during the spike: active=" + pool.getActiveConnections()
                    + ", idle=" + pool.getIdleConnections()
                    + ", threads waiting=" + pool.getThreadsAwaitingConnection());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
