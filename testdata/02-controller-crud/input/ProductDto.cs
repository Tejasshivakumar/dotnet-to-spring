using System.ComponentModel.DataAnnotations;

namespace Catalog.Dtos;

public class ProductDto
{
    public int Id { get; set; }

    [Required]
    [StringLength(100)]
    public string Name { get; set; } = string.Empty;

    public decimal Price { get; set; }
}
