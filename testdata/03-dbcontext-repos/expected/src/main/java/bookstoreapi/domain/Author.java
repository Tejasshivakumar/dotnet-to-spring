package bookstoreapi.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;

@Entity
@Table(name = "authors")
public class Author {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @NotNull
  @Size(max = 120)
  @Column(length = 120)
  private String name = "";

  @Email private String contactEmail;

  private LocalDate bornOn;

  @OneToMany(mappedBy = "author", fetch = FetchType.LAZY)
  @JsonIgnore
  private Collection<Book> books = new ArrayList<>();

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getContactEmail() {
    return contactEmail;
  }

  public void setContactEmail(String contactEmail) {
    this.contactEmail = contactEmail;
  }

  public LocalDate getBornOn() {
    return bornOn;
  }

  public void setBornOn(LocalDate bornOn) {
    this.bornOn = bornOn;
  }

  public Collection<Book> getBooks() {
    return books;
  }

  public void setBooks(Collection<Book> books) {
    this.books = books;
  }
}
