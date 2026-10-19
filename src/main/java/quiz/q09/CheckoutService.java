package quiz.q09;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckoutService {

    private final CatRepo repo;
    private final OrderWriter writer;

    CheckoutService(CatRepo repo, OrderWriter writer) { this.repo = repo; this.writer = writer; }

    /** The Friday version: the payment call happens INSIDE the transaction. */
    @Transactional
    public void checkout(String name) {
        repo.save(new Cat(name));
        callPaymentProvider();          // 1.5 s, and the DB connection is held the whole time
    }

    /** The fix: talk to the outside world first, open the transaction only to write. */
    public void checkoutFixed(String name) {
        callPaymentProvider();          // no transaction, no connection borrowed
        writer.save(name);              // transaction lives for a few milliseconds
    }

    private void callPaymentProvider() {
        try {
            Thread.sleep(1500);         // a slow third-party HTTP call
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
