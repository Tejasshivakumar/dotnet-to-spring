package bookstoreapi.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A book held in the store's catalogue. */
@Entity
@Table(name = "books")
public class Book {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @NotNull
  @Size(min = 1, max = 200)
  @Column(name = "title", length = 200)
  private String title = "";

  /** ISBN-13, digits only. Optional: older stock predates ISBN. */
  @Size(max = 13)
  @Column(length = 13)
  private String isbn;

  /** Retail price. Decimal, never double: this is money. */
  @Min(0)
  @Max(10000)
  @Column(name = "price", columnDefinition = "numeric(10,2)")
  private BigDecimal price;

  private int stockCount;

  @Enumerated(EnumType.STRING)
  private Genre genre;

  private LocalDateTime publishedOn;

  private LocalDateTime withdrawnOn;

  @Column(name = "author_id", insertable = false, updatable = false)
  private long authorId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JsonIgnore
  @JoinColumn(name = "author_id")
  private Author author;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getIsbn() {
    return isbn;
  }

  public void setIsbn(String isbn) {
    this.isbn = isbn;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public void setPrice(BigDecimal price) {
    this.price = price;
  }

  public int getStockCount() {
    return stockCount;
  }

  public void setStockCount(int stockCount) {
    this.stockCount = stockCount;
  }

  public Genre getGenre() {
    return genre;
  }

  public void setGenre(Genre genre) {
    this.genre = genre;
  }

  public LocalDateTime getPublishedOn() {
    return publishedOn;
  }

  public void setPublishedOn(LocalDateTime publishedOn) {
    this.publishedOn = publishedOn;
  }

  public LocalDateTime getWithdrawnOn() {
    return withdrawnOn;
  }

  public void setWithdrawnOn(LocalDateTime withdrawnOn) {
    this.withdrawnOn = withdrawnOn;
  }

  public long getAuthorId() {
    return authorId;
  }

  public void setAuthorId(long authorId) {
    this.authorId = authorId;
  }

  public Author getAuthor() {
    return author;
  }

  public void setAuthor(Author author) {
    this.author = author;
  }

  @Transient
  public boolean isInStock() {
    return stockCount > 0;
  }
}
