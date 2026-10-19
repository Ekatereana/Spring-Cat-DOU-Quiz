// 6. 1 query in the code, 4 queries in the log
// Run: ./gradlew q06
package quiz.q06;

import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import quiz.common.Cats;

@SpringBootApplication(scanBasePackages = "quiz.q06")
public class Q06NPlusOne {

    @Service
    static class CatService {
        private final CatRepo repo;
        CatService(CatRepo repo) { this.repo = repo; }

        @Transactional(readOnly = true)
        public int countToysNaive() {
            int total = 0;
            for (Cat cat : repo.findAll()) {      // 1 select
                total += cat.toys.size();         // + 1 select per cat
            }
            return total;
        }

        @Transactional(readOnly = true)
        public int countToysGraph() {
            int total = 0;
            for (Cat cat : repo.findAllByNameNotNull()) {   // 1 select, toys joined in
                total += cat.toys.size();
            }
            return total;
        }
    }

    public static void main(String[] args) {
        Cats.cat("Three cats, one findAll(), four queries");
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Q06NPlusOne.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .properties("spring.jpa.properties.hibernate.generate_statistics=true",
                            "logging.level.org.hibernate.stat=OFF")
                .run(args)) {

            CatRepo repo = ctx.getBean(CatRepo.class);
            CatService service = ctx.getBean(CatService.class);
            Statistics stats = ctx.getBean(jakarta.persistence.EntityManagerFactory.class)
                    .unwrap(SessionFactory.class).getStatistics();

            for (String name : List.of("Dobby", "Shadow", "Mila")) {
                Cat cat = new Cat(name);
                cat.add("mouse");
                cat.add("ball");
                repo.save(cat);
            }

            stats.clear();
            System.out.println("   findAll() + cat.toys.size():   toys = " + service.countToysNaive()
                    + ", SQL statements = " + Cats.RED + stats.getPrepareStatementCount() + Cats.R + "  (1 + N)");

            stats.clear();
            System.out.println("   @EntityGraph(\"toys\"):          toys = " + service.countToysGraph()
                    + ", SQL statements = " + Cats.G + stats.getPrepareStatementCount() + Cats.R);

            Cats.answer("N+1: one query for the parents, then one more per parent to initialise the lazy collection.");
            System.out.println("   Fixes: @EntityGraph, JOIN FETCH, a DTO projection, "
                    + "or @BatchSize / hibernate.default_batch_fetch_size (1 + N/batch).");
            System.out.println("   NOT a fix: fetch = EAGER - it makes every query load the collection, "
                    + "including the ones that never needed it.");
        }
    }
}
