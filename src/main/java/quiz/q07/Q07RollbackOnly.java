// 7. The exception was caught, so why UnexpectedRollbackException?
// Run: ./gradlew q07
package quiz.q07;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import quiz.common.Cats;

@SpringBootApplication(scanBasePackages = "quiz.q07")
public class Q07RollbackOnly {

    /** "Optional" side effect. Allowed to fail - the caller does not care. */
    @Service
    static class AuditService {
        private final CatRepo repo;
        AuditService(CatRepo repo) { this.repo = repo; }

        @Transactional                                   // REQUIRED: joins the caller's transaction
        public void audit() {
            repo.save(new Cat("audit-row"));
            throw new IllegalStateException("audit backend is down");
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)   // its own transaction
        public void auditIsolated() {
            repo.save(new Cat("audit-row"));
            throw new IllegalStateException("audit backend is down");
        }
    }

    @Service
    static class RegistrationService {
        private final CatRepo repo;
        private final AuditService audit;
        RegistrationService(CatRepo repo, AuditService audit) { this.repo = repo; this.audit = audit; }

        @Transactional
        public void register(String name) {
            repo.save(new Cat(name));
            try {
                audit.audit();                           // same transaction
            } catch (Exception e) {
                System.out.println("   caught and ignored: " + e.getMessage());
            }
        }

        @Transactional
        public void registerFixed(String name) {
            repo.save(new Cat(name));
            try {
                audit.auditIsolated();                   // separate transaction
            } catch (Exception e) {
                System.out.println("   caught and ignored: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        Cats.cat("Dobby is registered, the audit fails, everything burns");
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Q07RollbackOnly.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false).run(args)) {

            RegistrationService service = ctx.getBean(RegistrationService.class);
            CatRepo repo = ctx.getBean(CatRepo.class);

            try {
                service.register("Dobby");
                System.out.println("   registered");
            } catch (Exception e) {
                Cats.boom(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            System.out.println("   cats in DB: " + Cats.RED + repo.count() + Cats.R);

            System.out.println("\n   -- audit moved to REQUIRES_NEW --");
            try {
                service.registerFixed("Shadow");
                System.out.println("   registered");
            } catch (Exception e) {
                Cats.boom(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            System.out.println("   cats in DB: " + Cats.G + repo.count() + Cats.R);

            Cats.answer("Propagation.REQUIRED means the inner method runs in the SAME transaction. "
                    + "Its RuntimeException marked that transaction rollback-only. Catching the exception "
                    + "does not unmark it, so the commit fails with UnexpectedRollbackException.");
            System.out.println("   Fixes: REQUIRES_NEW for the optional work (separate connection, separate commit), "
                    + "@Transactional(noRollbackFor = ...), or do not call it inside the transaction at all "
                    + "(publish an event and handle it on AFTER_COMMIT).");
        }
    }
}
