package quiz.q05;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CatRepo extends JpaRepository<Cat, Long> {

    @Query("select c from Cat c join fetch c.toys where c.id = :id")
    Cat findWithToys(Long id);
}
