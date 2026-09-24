using Catalog.Dtos;

namespace Catalog.Services;

public interface IProductService
{
    Task<List<ProductDto>> SearchAsync(string? name, int page, int pageSize);

    Task<ProductDto?> FindAsync(int id);

    Task<ProductDto> CreateAsync(ProductDto product);

    Task<bool> UpdateAsync(int id, ProductDto product);

    Task<bool> DeleteAsync(int id);
}
