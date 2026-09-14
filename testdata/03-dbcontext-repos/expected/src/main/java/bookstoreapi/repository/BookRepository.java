package bookstoreapi.repository;

import bookstoreapi.domain.Book;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link Book}. */
public interface BookRepository extends JpaRepository<Book, Long> {}
