package todo.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Keyed by convention too, as {Type}Id, with a Guid EF generates. */
@Entity
public class TodoList {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID todoListId;

  private String title = "";

  @OneToMany(mappedBy = "list", fetch = FetchType.LAZY)
  @JsonIgnore
  private List<TodoItem> items = new ArrayList<>();

  public UUID getTodoListId() {
    return todoListId;
  }

  public void setTodoListId(UUID todoListId) {
    this.todoListId = todoListId;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public List<TodoItem> getItems() {
    return items;
  }

  public void setItems(List<TodoItem> items) {
    this.items = items;
  }
}
