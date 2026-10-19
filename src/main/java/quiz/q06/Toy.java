package quiz.q06;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "toy")
public class Toy {
    @Id @GeneratedValue public Long id;
    public String name;
    @ManyToOne(fetch = FetchType.LAZY) public Cat cat;
    protected Toy() { }
    public Toy(String name, Cat cat) { this.name = name; this.cat = cat; }
}
