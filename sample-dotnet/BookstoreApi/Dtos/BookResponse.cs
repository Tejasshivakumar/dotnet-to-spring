using System.Text.Json.Serialization;
using BookstoreApi.Models;

namespace BookstoreApi.Dtos;

public class BookResponse
{
    public long Id { get; set; }

    public string Title { get; set; } = string.Empty;

    [JsonPropertyName("isbn13")]
    public string? Isbn { get; set; }

    public decimal Price { get; set; }

    public Genre Genre { get; set; }

    public string AuthorName { get; set; } = string.Empty;

    public bool InStock { get; set; }

    [JsonIgnore]
    public DateTime PublishedOn { get; set; }
}
