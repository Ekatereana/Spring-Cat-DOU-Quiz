package quiz.q01;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Separate bean, so the short transaction really goes through the proxy (see question 2). */
@Service
public class OrderWriter {

    private final CatRepo repo;

    OrderWriter(CatRepo repo) { this.repo = repo; }

    @Transactional
    public void save(String name) { repo.save(new Cat(name)); }
}
