using System.ComponentModel.DataAnnotations;
using System.ComponentModel.DataAnnotations.Schema;

namespace BookstoreApi.Models;

/// <summary>
/// A book held in the store's catalogue.
/// </summary>
[Table("books")]
public class Book
{
    [Key]
    [DatabaseGenerated(DatabaseGeneratedOption.Identity)]
    public long Id { get; set; }

    [Required]
    [StringLength(200, MinimumLength = 1)]
    [Column("title")]
    public string Title { get; set; } = string.Empty;

    /// <summary>
    /// ISBN-13, digits only. Optional: older stock predates ISBN.
    /// </summary>
    [StringLength(13)]
    public string? Isbn { get; set; }

    /// <summary>
    /// Retail price. Decimal, never double: this is money.
    /// </summary>
    [Range(0, 10000)]
    [Column("price", TypeName = "numeric(10,2)")]
    public decimal Price { get; set; }

    public int StockCount { get; set; }

    public Genre Genre { get; set; }

    public DateTime PublishedOn { get; set; }

    public DateTime? WithdrawnOn { get; set; }

    [ForeignKey("AuthorId")]
    public long AuthorId { get; set; }

    public Author? Author { get; set; }

    [NotMapped]
    public bool InStock => StockCount > 0;
}
