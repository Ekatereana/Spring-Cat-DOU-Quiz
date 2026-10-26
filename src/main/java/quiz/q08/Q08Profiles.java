// 8. The profile trap: a default bean with no @Profile, and a feature nobody switched on
// Run: ./gradlew q08
package quiz.q08;

import java.util.List;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import quiz.common.Cats;

public class Q08Profiles {

    interface Feeder { String feed(); }

    static final class RealFeeder implements Feeder {
        public String feed() { return "real food"; }
    }

    static final class MockFeeder implements Feeder {
        public String feed() { return "plastic mouse"; }
    }

    static final class CatService {
        private final Feeder feeder;
        CatService(Feeder feeder) { this.feeder = feeder; }
        String dinner() { return feeder.feed(); }
    }

    // ------------------------------------------------ the "default" bean has no @Profile
    @Configuration(proxyBeanMethods = false)
    static class DefaultAndMock {
        @Bean Feeder realFeeder() { return new RealFeeder(); }

        @Profile("test")
        @Bean Feeder mockFeeder() { return new MockFeeder(); }

        @Bean CatService catService(Feeder feeder) { return new CatService(feeder); }
    }

    // ------------------------------------------------------- the fix: exclude the profile
    @Configuration(proxyBeanMethods = false)
    static class NotTestAndMock {
        @Profile("!test")
        @Bean Feeder realFeeder() { return new RealFeeder(); }

        @Profile("test")
        @Bean Feeder mockFeeder() { return new MockFeeder(); }

        @Bean CatService catService(Feeder feeder) { return new CatService(feeder); }
    }

    // --------------------------------- a whole feature behind a profile nobody activated
    interface NightJob { String name(); }

    @Configuration(proxyBeanMethods = false)
    static class ProfiledJobs {
        @Profile("prod")
        @Bean NightJob billingJob() { return () -> "billing"; }

        @Bean Scheduler scheduler(List<NightJob> jobs) { return new Scheduler(jobs); }
    }

    static final class Scheduler {
        private final List<NightJob> jobs;
        Scheduler(List<NightJob> jobs) { this.jobs = jobs; }
        String report() { return jobs.size() + " job(s): " + jobs.stream().map(NightJob::name).toList(); }
    }

    public static void main(String[] args) {
        Cats.cat("Dobby checks who feeds him in which profile");

        System.out.println("-- default bean WITHOUT @Profile, no profile active --");
        run(DefaultAndMock.class);

        System.out.println("\n-- the same context with --spring.profiles.active=test --");
        run(DefaultAndMock.class, "test");

        System.out.println("\n-- the fix: @Profile(\"!test\") on the default bean, profile test --");
        run(NotTestAndMock.class, "test");

        System.out.println("\n-- a feature behind @Profile(\"prod\"), running without it --");
        run(ProfiledJobs.class);

        System.out.println("\n-- the same context with --spring.profiles.active=prod --");
        run(ProfiledJobs.class, "prod");

        Cats.answer("A bean with no @Profile is active in EVERY profile, so it does not step aside "
                + "for the profiled one: both definitions exist and the injection point sees two "
                + "candidates -> NoUniqueBeanDefinitionException.");
        System.out.println("   Use @Profile(\"!test\") (or @ConditionalOnMissingBean) to make a real fallback.");
        System.out.println("   And a collection injection point accepts ZERO candidates: a feature hidden");
        System.out.println("   behind an inactive profile starts silently switched off - no error anywhere.");
    }

    private static void run(Class<?> config, String... profiles) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(config)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .profiles(profiles).run()) {

            context.getBeanProvider(CatService.class).ifAvailable(
                    service -> System.out.println("   dinner: " + service.dinner()));
            context.getBeanProvider(Scheduler.class).ifAvailable(
                    scheduler -> System.out.println("   scheduler has " + scheduler.report()));
            System.out.println("   started ok");
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null) { root = root.getCause(); }
            Cats.boom(root.getClass().getSimpleName() + ": "
                    + String.valueOf(root.getMessage()).lines().findFirst().orElse(""));
        }
    }
}
