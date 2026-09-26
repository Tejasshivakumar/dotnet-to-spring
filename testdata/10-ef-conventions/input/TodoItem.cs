namespace Todo.Models;

/// <summary>
/// No attributes at all: EF finds the key by convention (a property named Id)
/// and generates its value on insert.
/// </summary>
public class TodoItem
{
    public long Id { get; set; }

    public string? Name { get; set; }

    public bool IsComplete { get; set; }

    public TodoList? List { get; set; }
}
