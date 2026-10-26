// 7. The read-only migration that rewrote the table
// Run: ./gradlew q07
package quiz.q07;

import java.util.List;
import java.util.function.IntSupplier;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import quiz.common.Cats;

@SpringBootApplication(scanBasePackages = "quiz.q07")
public class Q07DirtyChecking {

    private static CatRepo repo;
    private static Statistics stats;

    public static void main(String[] args) {
        Cats.cat("Dobby's dry run: read the rows, change nothing, save nothing");

        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Q07DirtyChecking.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .properties("spring.jpa.properties.hibernate.generate_statistics=true")
                .run(args)) {

            repo = ctx.getBean(CatRepo.class);
            MigrationPreview preview = ctx.getBean(MigrationPreview.class);
            stats = ctx.getBean(EntityManagerFactory.class).unwrap(SessionFactory.class).getStatistics();

            phase("-- dry run inside a plain @Transactional method --", preview::preview);
            phase("-- the same code, @Transactional(readOnly = true) --", preview::previewReadOnly);
            phase("-- the same count, read as a projection (no managed entities) --",
                    preview::previewWithProjection);

            Cats.answer("Hibernate does not need save(): every entity loaded in a transaction is MANAGED. "
                    + "At commit the session auto-flushes, compares each entity with the snapshot taken at "
                    + "load time (dirty checking) and issues an UPDATE for every field that differs.");
            System.out.println("   The dry run committed, so there was nothing left to roll back: reverting the "
                    + "release reverts the code, not the rows. The data had already been rewritten by the "
                    + "transaction that 'only read'.");
            System.out.println("   readOnly = true puts the Hibernate session in FlushMode.MANUAL, so the "
                    + "auto-flush on commit never happens (it also skips the snapshots, which saves memory).");
            System.out.println("   Stronger still: never mutate a managed entity you do not intend to save - "
                    + "read a projection or a DTO, or detach the entity first.");
        }
    }

    private static void phase(String title, IntSupplier dryRun) {
        seed();
        System.out.println("\n   " + title);
        System.out.println("   before: " + names());
        long updatesBefore = stats.getEntityUpdateCount();
        int wouldChange = dryRun.getAsInt();
        long updates = stats.getEntityUpdateCount() - updatesBefore;
        System.out.println("   the dry run reports: " + wouldChange + " rows would change");
        System.out.println("   after:  " + names());
        String verdict = updates == 0 ? Cats.G + "UPDATE statements issued: 0" + Cats.R
                : Cats.RED + "UPDATE statements issued: " + updates + "  <-- nobody called save()" + Cats.R;
        System.out.println("   " + verdict);
    }

    private static void seed() {
        repo.deleteAll();
        repo.saveAll(List.of(new Cat("  Dobby  "), new Cat("MURZIK"), new Cat("simba")));
    }

    /** Reads the rows in a fresh transaction, so this is what the table really holds. */
    private static String names() {
        return repo.findAllNames().stream().map(n -> "[" + n + "]").toList().toString();
    }
}
