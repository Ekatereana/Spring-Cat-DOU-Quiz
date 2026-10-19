// 4. Nobody called save() - why did the UPDATE happen?
// Run: ./gradlew q04
package quiz.q04;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import quiz.common.Cats;

@SpringBootApplication(scanBasePackages = "quiz.q04")
public class Q04DirtyChecking {

    @Service
    static class CatService {
        private final CatRepo repo;
        CatService(CatRepo repo) { this.repo = repo; }

        /** Only wanted a pretty name for the report. No save() anywhere. */
        @Transactional
        public String report(Long id) {
            Cat cat = repo.findById(id).orElseThrow();
            cat.name = cat.name.toUpperCase();        // "just for the report"
            return "report for " + cat.name;
        }

        @Transactional(readOnly = true)
        public String reportReadOnly(Long id) {
            Cat cat = repo.findById(id).orElseThrow();
            cat.name = cat.name.toUpperCase();
            return "report for " + cat.name;
        }

        @Transactional(readOnly = true)
        public String nameInDb(Long id) { return repo.findById(id).orElseThrow().name; }
    }

    public static void main(String[] args) {
        Cats.cat("Dobby renames himself without anyone calling save()");
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Q04DirtyChecking.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .properties("spring.jpa.show-sql=true",
                            "spring.jpa.properties.hibernate.format_sql=false")
                .run(args)) {

            CatService service = ctx.getBean(CatService.class);
            CatRepo repo = ctx.getBean(CatRepo.class);

            Long dobby = repo.save(new Cat("dobby")).id;
            Long shadow = repo.save(new Cat("shadow")).id;

            System.out.println("\n   -- report() on a normal @Transactional --");
            System.out.println("   " + service.report(dobby));
            System.out.println("   name in DB now: " + Cats.RED + service.nameInDb(dobby) + Cats.R
                    + "   <- an UPDATE was flushed, nobody asked for it");

            System.out.println("\n   -- the same code under @Transactional(readOnly = true) --");
            System.out.println("   " + service.reportReadOnly(shadow));
            System.out.println("   name in DB now: " + Cats.G + service.nameInDb(shadow) + Cats.R
                    + "   <- untouched");

            Cats.answer("A managed entity is dirty-checked at flush time. Any setter inside the "
                    + "transaction becomes an UPDATE - save() is irrelevant.");
            System.out.println("   Fixes: @Transactional(readOnly = true) (Hibernate switches to FlushMode.MANUAL), "
                    + "a DTO projection instead of the entity, entityManager.detach(cat), "
                    + "or simply: never mutate an entity you only read.");
        }
    }
}
