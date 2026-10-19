package quiz.q06;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "cat")
public class Cat {
    @Id @GeneratedValue public Long id;
    public String name;

    @OneToMany(mappedBy = "cat", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    public List<Toy> toys = new ArrayList<>();

    protected Cat() { }
    public Cat(String name) { this.name = name; }
    public void add(String toy) { toys.add(new Toy(toy, this)); }
}
