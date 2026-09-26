namespace Todo.Models;

/// <summary>Keyed by convention too, as {Type}Id, with a Guid EF generates.</summary>
public class TodoList
{
    public Guid TodoListId { get; set; }

    public string Title { get; set; } = string.Empty;

    public List<TodoItem> Items { get; set; } = new();
}
