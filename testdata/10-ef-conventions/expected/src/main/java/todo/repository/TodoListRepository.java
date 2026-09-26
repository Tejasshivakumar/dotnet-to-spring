package todo.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import todo.domain.TodoList;

/** Spring Data repository for {@link TodoList}. */
public interface TodoListRepository extends JpaRepository<TodoList, UUID> {}
