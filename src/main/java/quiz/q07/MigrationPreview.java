package quiz.q07;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "dry run" of a data migration: read every row, normalise the name in memory,
 * count how many rows WOULD change. Nothing calls save(), nothing calls flush().
 */
@Service
public class MigrationPreview {

    private final CatRepo repo;

    MigrationPreview(CatRepo repo) { this.repo = repo; }

    @Transactional
    public int preview() { return normaliseInMemory(); }

    @Transactional(readOnly = true)
    public int previewReadOnly() { return normaliseInMemory(); }

    @Transactional(readOnly = true)
    public int previewWithProjection() {
        int wouldChange = 0;
        for (String name : repo.findAllNames()) {
            if (!name.equals(normalise(name))) { wouldChange++; }
        }
        return wouldChange;
    }

    private int normaliseInMemory() {
        int wouldChange = 0;
        for (Cat cat : repo.findAll()) {          // managed entities
            String normalised = normalise(cat.name);
            if (!normalised.equals(cat.name)) {
                cat.name = normalised;            // "just for the report"
                wouldChange++;
            }
        }
        return wouldChange;                       // no save(), no flush()
    }

    private String normalise(String name) { return name.trim().toLowerCase(); }
}
