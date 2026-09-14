using System.ComponentModel.DataAnnotations;
using BookstoreApi.Models;

namespace BookstoreApi.Dtos;

public class CreateBookRequest
{
    [Required]
    [StringLength(200, MinimumLength = 1)]
    public string Title { get; set; } = string.Empty;

    [StringLength(13)]
    public string? Isbn { get; set; }

    [Range(0, 10000)]
    public decimal Price { get; set; }

    public int StockCount { get; set; }

    public Genre Genre { get; set; }

    public DateTime PublishedOn { get; set; }

    [Required]
    public long AuthorId { get; set; }
}
