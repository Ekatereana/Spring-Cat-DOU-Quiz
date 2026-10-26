// 2. The refactor that moved the warm-up into an ApplicationReadyEvent listener.
//    Part 1: who runs first?   Part 2: what happens when the warm-up throws?
// Run: ./gradlew q02
package quiz.q02;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;

import quiz.common.Cats;

public class Q02ReadinessEvent {

    /** Pretend: the cache every request depends on. */
    static final class Cache {
        private final boolean failing;
        private volatile boolean warm;

        Cache(boolean failing) { this.failing = failing; }

        void loadTopCats() {
            System.out.println("            warming up the cache (300 ms)...");
            sleep(300);
            if (failing) {
                throw new IllegalStateException("cat-service returned 503");
            }
            warm = true;
        }

        boolean isWarm() { return warm; }
    }

    /** Pretend: Eureka / Consul / a Kubernetes readiness flag. */
    static final class Discovery {
        void register(Cache cache) {
            System.out.println("   [discovery] register()  <- cache warm = " + cache.isWarm()
                    + (cache.isWarm() ? "" : "   <-- traffic arrives on a cold cache"));
        }
    }

    /** Printed by every phase, so you can see how far the startup got. */
    static class Trace {
        @EventListener(ApplicationStartedEvent.class)
        void onStarted() { System.out.println("   [event]     ApplicationStartedEvent (runners NOT run yet)"); }

        @EventListener(ApplicationFailedEvent.class)
        void onFailed() { System.out.println("   [event]     ApplicationFailedEvent"); }

        @EventListener(ContextClosedEvent.class)
        void onClosed() { System.out.println("   [event]     ContextClosedEvent      <- context is shutting down"); }
    }

    // ------------------------------------------------------------------ before the refactor
    // The warm-up is a runner: every runner finishes before ApplicationReadyEvent.
    @Configuration(proxyBeanMethods = false)
    static class RunnerAndEvent {

        @Bean Trace trace() { return new Trace(); }
        @Bean Cache cache() { return new Cache(false); }
        @Bean Discovery discovery() { return new Discovery(); }

        @Bean ApplicationRunner warmUp(Cache cache) {
            return args -> {
                System.out.println("   [runner]    warmUpCache()");
                cache.loadTopCats();
            };
        }

        @Bean Register register(Cache cache, Discovery discovery) { return new Register(cache, discovery); }

        static class Register {
            private final Cache cache;
            private final Discovery discovery;

            Register(Cache cache, Discovery discovery) { this.cache = cache; this.discovery = discovery; }

            @EventListener(ApplicationReadyEvent.class)
            void publishUp() {
                System.out.println("   [event]     ApplicationReadyEvent   (runners finished)");
                System.out.println("   [listener]  publishUp()");
                discovery.register(cache);
            }
        }
    }

    // ------------------------------------------------------------------- after the refactor
    // Both things listen to the same event. @Order here only makes one of the
    // two legal orders reproducible - without it Spring guarantees nothing.
    @Configuration(proxyBeanMethods = false)
    static class BothOnReadyEvent {

        @Bean Trace trace() { return new Trace(); }
        @Bean Cache cache() { return new Cache(false); }
        @Bean Discovery discovery() { return new Discovery(); }
        @Bean Listeners listeners(Cache cache, Discovery discovery) { return new Listeners(cache, discovery); }

        static class Listeners {
            private final Cache cache;
            private final Discovery discovery;

            Listeners(Cache cache, Discovery discovery) { this.cache = cache; this.discovery = discovery; }

            @Order(1)
            @EventListener(ApplicationReadyEvent.class)
            void publishUp() {
                System.out.println("   [listener]  publishUp()");
                discovery.register(cache);
            }

            @Order(2)
            @EventListener(ApplicationReadyEvent.class)
            void warmUpCache() {
                System.out.println("   [listener]  warmUpCache()");
                cache.loadTopCats();
            }
        }
    }

    // ---------------------------------------------------- bonus: the warm-up listener throws
    @Configuration(proxyBeanMethods = false)
    static class WarmUpThrows {

        @Bean Trace trace() { return new Trace(); }
        @Bean Cache cache() { return new Cache(true); }
        @Bean Discovery discovery() { return new Discovery(); }
        @Bean Listeners listeners(Cache cache, Discovery discovery) { return new Listeners(cache, discovery); }

        static class Listeners {
            private final Cache cache;
            private final Discovery discovery;

            Listeners(Cache cache, Discovery discovery) { this.cache = cache; this.discovery = discovery; }

            @Order(1)
            @EventListener(ApplicationReadyEvent.class)
            void warmUpCache() {
                System.out.println("   [listener]  warmUpCache()");
                cache.loadTopCats();
            }

            @Order(2)
            @EventListener(ApplicationReadyEvent.class)
            void publishUp() {
                System.out.println("   [listener]  publishUp()  <- never printed");
                discovery.register(cache);
            }
        }
    }

    // -------------------------------------- bonus: the same throw, hidden behind @Async
    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class WarmUpThrowsAsync {

        @Bean Trace trace() { return new Trace(); }
        @Bean Cache cache() { return new Cache(true); }
        @Bean Discovery discovery() { return new Discovery(); }
        @Bean Listeners listeners(Cache cache, Discovery discovery) { return new Listeners(cache, discovery); }

        static class Listeners {
            private final Cache cache;
            private final Discovery discovery;

            Listeners(Cache cache, Discovery discovery) { this.cache = cache; this.discovery = discovery; }

            @Async
            @EventListener(ApplicationReadyEvent.class)
            void warmUpCache() {
                System.out.println("   [listener]  warmUpCache() on " + Thread.currentThread().getName());
                cache.loadTopCats();
            }

            @EventListener(ApplicationReadyEvent.class)
            void publishUp() {
                System.out.println("   [listener]  publishUp()");
                discovery.register(cache);
            }
        }
    }

    public static void main(String[] args) {
        Cats.cat("Dobby warms a cache, then tells discovery he is ready");

        System.out.println("-- BEFORE: warm-up in an ApplicationRunner, registration on the event --");
        run(RunnerAndEvent.class, args);

        System.out.println("\n-- AFTER: both on ApplicationReadyEvent, one of the legal orders --");
        run(BothOnReadyEvent.class, args);

        System.out.println("\n-- BONUS: the warm-up listener throws --");
        run(WarmUpThrows.class, args);

        System.out.println("\n-- BONUS: the same throw, listener marked @Async --");
        run(WarmUpThrowsAsync.class, args);

        Cats.answer("Runners are ordered against the event; two listeners of the same event are not.");
        System.out.println("   Every ApplicationRunner / CommandLineRunner finishes before ApplicationReadyEvent");
        System.out.println("   is published. Between two @EventListener methods the order comes from reflection,");
        System.out.println("   so the refactor replaced a guarantee with a coin flip.");
        System.out.println("   A listener also runs on the publishing thread: its exception goes back to the");
        System.out.println("   publisher - here SpringApplication.run() itself, so the context closes and the");
        System.out.println("   process exits non-zero. @Async only hides that: 'ready' and broken.");
    }

    private static void run(Class<?> config, String[] args) {
        ConfigurableApplicationContext context = null;
        try {
            context = new SpringApplicationBuilder(config)
                    .web(WebApplicationType.NONE)
                    .bannerMode(Banner.Mode.OFF)
                    .logStartupInfo(false)
                    .run(args);
            sleep(500);     // give an @Async listener time to fail on its own thread
            System.out.println("   run() returned, application is alive: running=" + context.isRunning());
        } catch (Throwable failure) {
            Cats.boom("run() threw " + failure.getClass().getSimpleName() + ": " + failure.getMessage()
                    + "  -> exit code != 0, Kubernetes restarts the pod");
        } finally {
            if (context != null && context.isActive()) {
                context.close();
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
