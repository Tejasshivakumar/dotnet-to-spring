using BookstoreApi.Models;

namespace BookstoreApi.Services;

public interface IAuthorService
{
    Task<List<Author>> GetAllAsync();

    Task<Author?> GetByIdAsync(long id);
}
