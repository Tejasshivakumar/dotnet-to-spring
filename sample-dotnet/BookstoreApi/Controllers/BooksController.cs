using BookstoreApi.Dtos;
using BookstoreApi.Services;
using Microsoft.AspNetCore.Mvc;

namespace BookstoreApi.Controllers;

[ApiController]
[Route("api/[controller]")]
public class BooksController : ControllerBase
{
    private readonly IBookService _bookService;

    public BooksController(IBookService bookService)
    {
        _bookService = bookService;
    }

    [HttpGet]
    public async Task<ActionResult<List<BookResponse>>> GetAll()
    {
        var books = await _bookService.GetAllAsync();
        return Ok(books);
    }

    [HttpGet("{id}")]
    public async Task<ActionResult<BookResponse>> GetById([FromRoute] long id)
    {
        var book = await _bookService.GetByIdAsync(id);
        if (book == null)
        {
            return NotFound();
        }

        return Ok(book);
    }

    [HttpPost]
    public async Task<ActionResult<BookResponse>> Create([FromBody] CreateBookRequest request)
    {
        var created = await _bookService.CreateAsync(request);
        return CreatedAtAction(nameof(GetById), new { id = created.Id }, created);
    }

    [HttpDelete("{id}")]
    public async Task<IActionResult> Delete([FromRoute] long id)
    {
        var deleted = await _bookService.DeleteAsync(id);
        if (!deleted)
        {
            return NotFound();
        }

        return NoContent();
    }

    [HttpGet("value")]
    public ActionResult<decimal> TotalValue()
    {
        return Ok(_bookService.TotalCatalogueValue());
    }
}
