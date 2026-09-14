using System.ComponentModel.DataAnnotations;
using System.ComponentModel.DataAnnotations.Schema;

namespace BookstoreApi.Models;

[Table("authors")]
public class Author
{
    [Key]
    [DatabaseGenerated(DatabaseGeneratedOption.Identity)]
    public long Id { get; set; }

    [Required]
    [StringLength(120)]
    public string Name { get; set; } = string.Empty;

    [EmailAddress]
    public string? ContactEmail { get; set; }

    public DateOnly? BornOn { get; set; }

    public ICollection<Book> Books { get; set; } = new List<Book>();
}
