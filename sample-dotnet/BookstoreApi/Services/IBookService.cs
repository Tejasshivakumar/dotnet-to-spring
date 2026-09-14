using BookstoreApi.Dtos;
using BookstoreApi.Models;

namespace BookstoreApi.Services;

public interface IBookService
{
    Task<List<BookResponse>> GetAllAsync();

    Task<BookResponse?> GetByIdAsync(long id);

    Task<BookResponse> CreateAsync(CreateBookRequest request);

    Task<bool> DeleteAsync(long id);

    decimal TotalCatalogueValue();

    IEnumerable<Book> StreamInStock();
}
