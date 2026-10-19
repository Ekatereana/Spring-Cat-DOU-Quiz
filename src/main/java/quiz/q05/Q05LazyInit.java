// 5. LazyInitializationException: explain the failure
// Run: ./gradlew q05
package quiz.q05;

import java.util.List;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import quiz.common.Cats;

@SpringBootApplication(scanBasePackages = "quiz.q05")
public class Q05LazyInit {

    @Service
    static class CatService {
        private final CatRepo repo;
        CatService(CatRepo repo) { this.repo = repo; }

        /** Transaction ends when this method returns. */
        @Transactional(readOnly = true)
        public Cat load(Long id) { return repo.findById(id).orElseThrow(); }

        @Transactional(readOnly = true)
        public Cat loadWithToys(Long id) { return repo.findWithToys(id); }
    }

    public static void main(String[] args) {
        Cats.cat("Dobby's toys vanish the moment the transaction closes");
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Q05LazyInit.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false).run(args)) {

            CatService service = ctx.getBean(CatService.class);
            CatRepo repo = ctx.getBean(CatRepo.class);

            Cat dobby = new Cat("Dobby");
            dobby.add("mouse");
            dobby.add("ball");
            Long id = repo.save(dobby).id;

            Cat detached = service.load(id);
            System.out.println("   cat loaded fine: " + detached.name);
            try {
                System.out.println("   toys: " + detached.toys.size());
            } catch (Exception e) {
                Cats.boom(e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            Cat eager = service.loadWithToys(id);
            System.out.println("\n   with 'join fetch c.toys': toys = " + Cats.G + eager.toys.size() + Cats.R);

            Cats.answer("The collection is a lazy proxy. Outside the transaction the persistence context "
                    + "is gone, so it cannot be initialised - LazyInitializationException.");
            System.out.println("   Fixes: JOIN FETCH, @EntityGraph, a DTO projection, "
                    + "or touch the collection while the transaction is still open.");
            System.out.println("   NOT a fix: fetch = EAGER (kills every other query) or "
                    + "spring.jpa.open-in-view = true (hides the bug, holds the connection for the whole request).");
        }
    }
}
