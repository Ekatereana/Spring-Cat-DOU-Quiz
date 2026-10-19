package quiz.q05;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "cat")
public class Cat {
    @Id @GeneratedValue public Long id;
    public String name;
    protected Cat() { }
    public Cat(String name) { this.name = name; }
}
