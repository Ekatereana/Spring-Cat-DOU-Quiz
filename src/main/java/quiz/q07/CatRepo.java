package quiz.q07;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CatRepo extends JpaRepository<Cat, Long> {

    /** A projection: Hibernate returns strings, not managed entities. */
    @Query("select c.name from Cat c order by c.id")
    List<String> findAllNames();
}
