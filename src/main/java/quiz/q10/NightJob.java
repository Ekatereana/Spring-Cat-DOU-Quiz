package quiz.q10;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The nightly job. Timeout is set on every method - 2 seconds instead of the production 20. */
@Service
public class NightJob {

    private final CatRepo repo;
    private final ChunkWriter chunks;

    NightJob(CatRepo repo, ChunkWriter chunks) { this.repo = repo; this.chunks = chunks; }

    /** Version 1: the loop keeps talking to the database. */
    @Transactional(timeout = 2)
    public int loopWithQueries() {
        int done = 0;
        for (int i = 1; i <= 20; i++) {
            repo.save(new Cat("night-" + i));
            repo.count();                 // a statement on every iteration
            done++;
            pause(300);
        }
        return done;
    }

    /** Version 2: the loop waits for something that is not the database. */
    @Transactional(timeout = 2)
    public int loopWithSlowCall() {
        repo.save(new Cat("night-remote"));
        pause(5000);                      // third-party API with no socket timeout
        return 1;
    }

    /** The fix: the slow part happens outside, each chunk gets its own short transaction. */
    public int chunked() {
        int done = 0;
        for (int i = 1; i <= 5; i++) {
            pause(1000);                  // the slow call, no transaction open
            chunks.writeChunk("chunk-" + i);
            done++;
        }
        return done;
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
