package bookstoreapi.repository;

import bookstoreapi.domain.Author;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link Author}. */
public interface AuthorRepository extends JpaRepository<Author, Long> {}
