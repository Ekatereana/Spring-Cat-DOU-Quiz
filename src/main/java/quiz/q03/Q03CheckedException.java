// 3. @Transactional + a checked exception: does the row survive?
// Run: ./gradlew q03
package quiz.q03;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import quiz.common.Cats;

@SpringBootApplication(scanBasePackages = "quiz.q03")
public class Q03CheckedException {

    static class NoFoodException extends Exception {          // CHECKED
        NoFoodException(String m) { super(m); }
    }

    @Service
    static class FeedingService {
        private final CatRepo repo;
        FeedingService(CatRepo repo) { this.repo = repo; }

        @Transactional
        public void feedChecked() throws NoFoodException {
            repo.save(new Cat("Dobby"));
            throw new NoFoodException("bowl is empty");       // checked
        }

        @Transactional
        public void feedUnchecked() {
            repo.save(new Cat("Shadow"));
            throw new IllegalStateException("bowl is empty"); // unchecked
        }

        @Transactional(rollbackFor = Exception.class)
        public void feedCheckedFixed() throws NoFoodException {
            repo.save(new Cat("Mila"));
            throw new NoFoodException("bowl is empty");
        }
    }

    public static void main(String[] args) {
        Cats.cat("Dobby, a checked exception and a transaction that commits anyway");
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Q03CheckedException.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false).run(args)) {

            FeedingService service = ctx.getBean(FeedingService.class);
            CatRepo repo = ctx.getBean(CatRepo.class);

            repo.deleteAll();
            try { service.feedChecked(); } catch (Exception e) { Cats.boom("checked:   " + e); }
            System.out.println("   rows after a CHECKED exception:   " + Cats.RED + repo.count() + Cats.R + "  <- committed!");

            repo.deleteAll();
            try { service.feedUnchecked(); } catch (Exception e) { Cats.boom("unchecked: " + e); }
            System.out.println("   rows after an UNCHECKED exception: " + Cats.G + repo.count() + Cats.R + "  <- rolled back");

            repo.deleteAll();
            try { service.feedCheckedFixed(); } catch (Exception e) { Cats.boom("checked + rollbackFor: " + e); }
            System.out.println("   rows with rollbackFor = Exception.class: " + Cats.G + repo.count() + Cats.R + "  <- rolled back");

            Cats.answer("Spring rolls back on RuntimeException and Error only. A checked exception commits.");
            System.out.println("   Fix: @Transactional(rollbackFor = Exception.class), or throw an unchecked exception.");
        }
    }
}
