package quiz.q06;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatRepo extends JpaRepository<Cat, Long> {

    @EntityGraph(attributePaths = "toys")
    List<Cat> findAllByNameNotNull();
}
