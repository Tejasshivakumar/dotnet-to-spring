package todo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import todo.domain.TodoItem;

/** Spring Data repository for {@link TodoItem}. */
public interface TodoItemRepository extends JpaRepository<TodoItem, Long> {}
