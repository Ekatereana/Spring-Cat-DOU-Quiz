// 4. Two beans needing each other
// Run: ./gradlew q04
package quiz.q04;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import quiz.common.Cats;

public class Q04CircularDeps {

    // ---- version A: constructor injection both ways -------------------------
    @Configuration
    static class BrokenConfig {
        @Service static class CatService {
            CatService(BowlService bowl) { }
        }
        @Service static class BowlService {
            BowlService(CatService cat) { }
        }
    }

    // ---- version B: one side is @Lazy ---------------------------------------
    @Configuration
    static class LazyConfig {
        @Service static class CatService {
            private final BowlService bowl;
            CatService(@Lazy BowlService bowl) { this.bowl = bowl; }
            String feed() { return bowl.fill() + " -> Dobby eats"; }
        }
        @Service static class BowlService {
            private final CatService cat;
            @Autowired BowlService(CatService cat) { this.cat = cat; }
            String fill() { return "bowl filled"; }
        }
    }

    public static void main(String[] args) {
        Cats.cat("Dobby needs the bowl, the bowl needs Dobby");

        System.out.println("   -- constructor injection on both sides --");
        try (ConfigurableApplicationContext ctx = build(BrokenConfig.class)) {
            System.out.println("   context started: " + ctx.getBeanDefinitionCount() + " beans");
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) { root = root.getCause(); }
            Cats.boom(root.getClass().getSimpleName() + ": " + firstLine(root.getMessage()));
            System.out.println("   the application never starts - this is a startup failure, not a runtime one");
        }

        System.out.println("\n   -- one side marked @Lazy --");
        try (ConfigurableApplicationContext ctx = build(LazyConfig.class)) {
            System.out.println("   " + Cats.G + ctx.getBean(LazyConfig.CatService.class).feed() + Cats.R);
        }

        Cats.answer("Constructor injection needs a finished object before it can build the other one, "
                + "so a cycle cannot be resolved: BeanCurrentlyInCreationException at startup.");
        System.out.println("   Fixes: @Lazy on one side (a proxy is injected, the real bean is built on first call), "
                + "setter/field injection (needs spring.main.allow-circular-references = true since Boot 2.6), "
                + "an ApplicationEventPublisher instead of a direct call, or - best - extract the shared logic "
                + "into a third bean and break the cycle.");
    }

    private static ConfigurableApplicationContext build(Class<?> config) {
        return new SpringApplicationBuilder(config)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .properties("spring.main.allow-circular-references=false")
                .run();
    }

    private static String firstLine(String message) {
        if (message == null) { return ""; }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
