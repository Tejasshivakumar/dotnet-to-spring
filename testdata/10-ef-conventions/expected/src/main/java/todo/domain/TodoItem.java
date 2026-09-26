package todo.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/**
 * No attributes at all: EF finds the key by convention (a property named Id) and generates its
 * value on insert.
 */
@Entity
public class TodoItem {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String name;

  private boolean isComplete;

  @ManyToOne(fetch = FetchType.LAZY)
  @JsonIgnore
  private TodoList list;

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

  public boolean isIsComplete() {
    return isComplete;
  }

  public void setIsComplete(boolean isComplete) {
    this.isComplete = isComplete;
  }

  public TodoList getList() {
    return list;
  }

  public void setList(TodoList list) {
    this.list = list;
  }
}
