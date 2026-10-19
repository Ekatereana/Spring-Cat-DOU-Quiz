package quiz.q05;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChunkWriter {

    private final CatRepo repo;

    ChunkWriter(CatRepo repo) { this.repo = repo; }

    @Transactional(timeout = 2)
    public void writeChunk(String name) { repo.save(new Cat(name)); }
}
