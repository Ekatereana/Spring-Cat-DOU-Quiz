// 9. Bonus: bean lifecycle order - which cat gets drawn?
// Run: ./gradlew q09
package quiz.q09;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import quiz.common.Cats;

@SpringBootApplication(scanBasePackages = "quiz.q09")
public class Q09Lifecycle {

    @Component
    static class CatBean implements InitializingBean {

        CatBean() { System.out.println(" > ^ < "); }                  // C: whiskers

        @PostConstruct
        void postConstruct() { System.out.println("( o.o )"); }       // B: face

        @Override
        public void afterPropertiesSet() { System.out.println(" /\\_/\\ "); }   // A: ears

        @EventListener(ApplicationReadyEvent.class)
        void ready() { System.out.println("  purr  "); }              // D: purring
    }

    @Bean
    ApplicationRunner runner() {
        return args -> System.out.println(" ~~~~~ ");                 // E: tail
    }

    public static void main(String[] args) {
        System.out.println("Dobby is assembled part by part. Watch the order:\n");
        new SpringApplicationBuilder(Q09Lifecycle.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .run(args).close();
        Cats.answer("constructor -> @PostConstruct -> afterPropertiesSet() -> ApplicationRunner -> ApplicationReadyEvent");
        System.out.println("   The constructor runs first but prints the whiskers, so the cat is drawn upside down "
                + "until @PostConstruct and afterPropertiesSet() put the face and ears on top.");
    }
}
