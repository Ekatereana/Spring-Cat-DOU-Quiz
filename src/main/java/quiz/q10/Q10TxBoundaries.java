// 10. timeout = 20s, yet the connection was open all night
// Run: ./gradlew q10
package quiz.q10;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import quiz.common.Cats;

@SpringBootApplication(scanBasePackages = "quiz.q10")
public class Q10TxBoundaries {

    private static HikariPoolMXBean pool;

    public static void main(String[] args) {
        Cats.cat("Dobby's nightly job: @Transactional(timeout = 2) and a connection that never lets go");

        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Q10TxBoundaries.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .properties("spring.datasource.hikari.maximum-pool-size=3").run(args)) {

            NightJob job = ctx.getBean(NightJob.class);
            CatRepo repo = ctx.getBean(CatRepo.class);
            pool = ctx.getBean(HikariDataSource.class).getHikariPoolMXBean();

            System.out.println("   timeout = 2 s on every method. Watch what the timeout actually stops.\n");

            System.out.println("   -- version 1: the loop keeps querying the database --");
            repo.deleteAll();
            timed(() -> job.loopWithQueries());
            System.out.println("   rows committed: " + repo.count());

            System.out.println("\n   -- version 2: the loop waits 5 s for a third-party API --");
            repo.deleteAll();
            timed(() -> job.loopWithSlowCall());
            System.out.println("   rows committed: " + repo.count());

            System.out.println("\n   -- the fix: slow call outside, one short transaction per chunk --");
            repo.deleteAll();
            timed(() -> job.chunked());
            System.out.println("   rows committed: " + Cats.G + repo.count() + Cats.R);

            Cats.answer("A transaction timeout is a DEADLINE, not a watchdog. Spring stores it in the "
                    + "resource holder and checks it when the next JDBC statement is issued (and passes "
                    + "the remaining seconds to Statement.setQueryTimeout). No statement, no check - a "
                    + "thread parked in an HTTP call keeps the connection checked out of the pool until "
                    + "it returns, timeout or not.");
            System.out.println("   That is the all-night connection: the transaction opened, the remote call hung "
                    + "without a socket timeout, and nothing in Spring or Hikari interrupts a borrowed connection.");
            System.out.println("   Fixes: connect/read timeouts on every HTTP and messaging client; no remote calls "
                    + "inside a transaction; chunked transactions instead of one loop-long transaction; "
                    + "DB-side guards (Postgres idle_in_transaction_session_timeout, statement_timeout); "
                    + "Hikari leakDetectionThreshold to get it in the log instead of in the morning.");
        }
    }

    private static void timed(Supplier<Integer> work) {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            while (running.get()) {
                NightJob.pause(1200);
                if (!running.get()) { return; }
                System.out.println("      t+" + (System.currentTimeMillis() - t0) / 1000 + "s  pool active="
                        + pool.getActiveConnections() + ", idle=" + pool.getIdleConnections());
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        long started = System.currentTimeMillis();
        try {
            int done = work.get();
            running.set(false);
            System.out.println("   finished after " + (System.currentTimeMillis() - started) + " ms, "
                    + done + " iterations, no exception");
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) { root = root.getCause(); }
            running.set(false);
            System.out.println("   stopped after " + (System.currentTimeMillis() - started) + " ms");
            Cats.boom(root.getClass().getSimpleName() + ": " + root.getMessage());
        }
    }
}
