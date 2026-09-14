using BookstoreApi.Data;
using BookstoreApi.Models;
using Microsoft.EntityFrameworkCore;

namespace BookstoreApi.Services;

public class AuthorService : IAuthorService
{
    private readonly BookstoreDbContext _db;

    public AuthorService(BookstoreDbContext db)
    {
        _db = db;
    }

    public async Task<List<Author>> GetAllAsync()
    {
        return await _db.Authors.OrderBy(a => a.Name).ToListAsync();
    }

    public async Task<Author?> GetByIdAsync(long id)
    {
        return await _db.Authors.FindAsync(id);
    }
}
