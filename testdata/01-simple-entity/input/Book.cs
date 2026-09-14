using System.ComponentModel.DataAnnotations;
using System.ComponentModel.DataAnnotations.Schema;

namespace Bookstore.Models;

[Table("books")]
public class Book
{
    [Key]
    [DatabaseGenerated(DatabaseGeneratedOption.Identity)]
    public long Id { get; set; }

    [Required]
    [StringLength(200)]
    [Column("title")]
    public string Title { get; set; } = string.Empty;

    [StringLength(13)]
    public string? Isbn { get; set; }

    public decimal Price { get; set; }

    public int StockCount { get; set; }

    public DateTime PublishedOn { get; set; }
}
