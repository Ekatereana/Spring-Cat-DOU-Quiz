// 2. Which event means "the application is really ready"?
// Run: ./gradlew q02
package quiz.q02;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import quiz.common.Cats;

@SpringBootApplication(scanBasePackages = "quiz.q02")
public class Q02ReadinessEvent {

    @Bean
    ApplicationRunner warmUpCache() {
        return args -> {
            Thread.sleep(300);                      // pretend: cache warm-up, migrations
            System.out.println("   [runner]  cache warmed up");
        };
    }

    @EventListener(ContextRefreshedEvent.class)
    void onRefreshed() {
        System.out.println("   [event]   ContextRefreshedEvent   (beans created, context refreshed)");
    }

    @EventListener(ApplicationStartedEvent.class)
    void onStarted() {
        System.out.println("   [event]   ApplicationStartedEvent (context up, runners NOT run yet)");
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        System.out.println("   [event]   ApplicationReadyEvent   (runners finished, app can serve traffic)");
    }

    public static void main(String[] args) {
        Cats.cat("Dobby waits for the application to be ready");
        new SpringApplicationBuilder(Q02ReadinessEvent.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(args)
                .close();
        Cats.answer("ApplicationReadyEvent is the last one: it fires AFTER every "
                + "ApplicationRunner / CommandLineRunner and after the web server accepts traffic.");
        System.out.println("   ContextRefreshedEvent can fire more than once (child contexts, actuator /refresh).");
        System.out.println("   ApplicationStartedEvent fires before the runners -> cache is still cold.");
    }
}
