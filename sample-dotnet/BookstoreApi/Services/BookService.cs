using BookstoreApi.Data;
using BookstoreApi.Dtos;
using BookstoreApi.Models;
using Microsoft.EntityFrameworkCore;

namespace BookstoreApi.Services;

public class BookService : IBookService
{
    private readonly BookstoreDbContext _db;

    public BookService(BookstoreDbContext db)
    {
        _db = db;
    }

    /// <summary>
    /// Every book, newest first, projected onto the response shape.
    /// </summary>
    public async Task<List<BookResponse>> GetAllAsync()
    {
        var books = await _db.Books.Include(b => b.Author).ToListAsync();
        return books
            .OrderBy(b => b.Title)
            .Select(b => ToResponse(b))
            .ToList();
    }

    public async Task<BookResponse?> GetByIdAsync(long id)
    {
        var book = await _db.Books.Include(b => b.Author).FirstOrDefaultAsync(b => b.Id == id);
        if (book == null)
        {
            return null;
        }

        return ToResponse(book);
    }

    public async Task<BookResponse> CreateAsync(CreateBookRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.Title))
        {
            throw new ArgumentException("Title is required");
        }

        var author = await _db.Authors.FindAsync(request.AuthorId);
        if (author == null)
        {
            throw new KeyNotFoundException($"No author with id {request.AuthorId}");
        }

        var book = new Book
        {
            Title = request.Title,
            Isbn = request.Isbn,
            Price = request.Price,
            StockCount = request.StockCount,
            Genre = request.Genre,
            PublishedOn = request.PublishedOn,
            AuthorId = request.AuthorId,
            Author = author
        };

        _db.Books.Add(book);
        await _db.SaveChangesAsync();

        return ToResponse(book);
    }

    public async Task<bool> DeleteAsync(long id)
    {
        var book = await _db.Books.FindAsync(id);
        if (book == null)
        {
            return false;
        }

        _db.Books.Remove(book);
        await _db.SaveChangesAsync();
        return true;
    }

    /// <summary>
    /// Total retail value of everything on the shelves.
    /// </summary>
    public decimal TotalCatalogueValue()
    {
        var books = _db.Books.ToList();
        decimal total = 0;
        foreach (var book in books)
        {
            total += book.Price * book.StockCount;
        }

        return total;
    }

    /// <summary>
    /// Lazily walks the catalogue. No Java equivalent: the migration tool should
    /// stub this and raise a finding rather than guess.
    /// </summary>
    public IEnumerable<Book> StreamInStock()
    {
        foreach (var book in _db.Books)
        {
            if (book.StockCount > 0)
            {
                yield return book;
            }
        }
    }

    private static BookResponse ToResponse(Book book)
    {
        return new BookResponse
        {
            Id = book.Id,
            Title = book.Title,
            Isbn = book.Isbn,
            Price = book.Price,
            Genre = book.Genre,
            AuthorName = book.Author?.Name ?? "Unknown",
            InStock = book.StockCount > 0,
            PublishedOn = book.PublishedOn
        };
    }
}
