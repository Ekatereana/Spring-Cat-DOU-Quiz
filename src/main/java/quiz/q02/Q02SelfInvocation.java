// 2. @Transactional on a method called from inside the same bean
// Run: ./gradlew q02
package quiz.q02;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import quiz.common.Cats;

@SpringBootApplication(scanBasePackages = "quiz.q02")
public class Q02SelfInvocation {

    @Service
    static class FeedingService {

        public void feedTwice() {                       // no annotation here
            System.out.println("   feedTwice()  tx active = " + tx());
            feedOnce();                          // self-invocation: this.feedOnce()
        }

        @Transactional
        public void feedOnce() {
            System.out.println("   feedOnce()   tx active = " + tx() + "   <- @Transactional is here");
        }

        private static String tx() {
            boolean active = TransactionSynchronizationManager.isActualTransactionActive();
            return (active ? Cats.G + "true" : Cats.RED + "false") + Cats.R;
        }
    }

    public static void main(String[] args) {
        Cats.cat("Dobby calls himself and loses the transaction");
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Q02SelfInvocation.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false).run(args)) {

            FeedingService service = ctx.getBean(FeedingService.class);
            System.out.println("   bean class = " + service.getClass().getSimpleName()
                    + "  (Spring proxied it, so the annotation IS being processed)");

            System.out.println("\n   call through the proxy: service.feedOnce()");
            service.feedOnce();

            System.out.println("\n   call through the proxy, then internally: service.feedTwice()");
            service.feedTwice();

            Cats.answer("Self-invocation never leaves the object, so the proxy is not involved "
                    + "and @Transactional does nothing.");
            System.out.println("   Fixes: move feedOnce() into another bean, inject self (@Lazy), "
                    + "AopContext.currentProxy() with @EnableAspectJAutoProxy(exposeProxy = true), "
                    + "or switch to AspectJ weaving.");
        }
    }
}
