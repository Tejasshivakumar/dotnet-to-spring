using Catalog.Dtos;
using Catalog.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Catalog.Controllers;

/// <summary>
/// Product catalogue endpoints.
/// </summary>
[ApiController]
[Route("api/[controller]")]
public class ProductsController : ControllerBase
{
    private readonly IProductService _products;

    public ProductsController(IProductService products)
    {
        _products = products;
    }

    [HttpGet]
    public async Task<ActionResult<List<ProductDto>>> Search(
        [FromQuery] string? name,
        [FromQuery] int page = 1,
        [FromQuery(Name = "size")] int pageSize = 20)
    {
        if (page < 1)
        {
            return BadRequest("page must be at least 1");
        }

        return await _products.SearchAsync(name, page, pageSize);
    }

    [HttpGet("{id:int}")]
    public async Task<ActionResult<ProductDto>> Get(int id)
    {
        var product = await _products.FindAsync(id);
        return product is null ? NotFound() : Ok(product);
    }

    [HttpPost]
    public async Task<ActionResult<ProductDto>> Create([FromBody] ProductDto product)
    {
        var created = await _products.CreateAsync(product);
        return CreatedAtAction(nameof(Get), new { id = created.Id }, created);
    }

    [HttpPut("{id:int}")]
    public async Task<IActionResult> Update(int id, ProductDto product)
    {
        if (id != product.Id)
        {
            return BadRequest();
        }

        var updated = await _products.UpdateAsync(id, product);
        return updated ? NoContent() : NotFound();
    }

    [HttpDelete("{id:int}")]
    [Authorize(Roles = "Admin")]
    public async Task<IActionResult> Delete(int id)
    {
        return await _products.DeleteAsync(id) ? NoContent() : NotFound();
    }
}
