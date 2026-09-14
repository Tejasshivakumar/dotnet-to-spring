using BookstoreApi.Models;
using BookstoreApi.Services;
using Microsoft.AspNetCore.Mvc;

namespace BookstoreApi.Controllers;

[ApiController]
[Route("api/[controller]")]
public class AuthorsController : ControllerBase
{
    private readonly IAuthorService _authorService;

    public AuthorsController(IAuthorService authorService)
    {
        _authorService = authorService;
    }

    [HttpGet]
    public async Task<ActionResult<List<Author>>> GetAll()
    {
        var authors = await _authorService.GetAllAsync();
        return Ok(authors);
    }

    [HttpGet("{id}")]
    public async Task<ActionResult<Author>> GetById([FromRoute] long id)
    {
        var author = await _authorService.GetByIdAsync(id);
        if (author == null)
        {
            return NotFound();
        }

        return Ok(author);
    }
}
