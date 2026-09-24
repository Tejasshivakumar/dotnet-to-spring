using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;

namespace Accounts.Dtos;

/// <summary>
/// Every validation attribute the attribute mapper knows, on one request.
/// </summary>
public class RegistrationRequest
{
    [Required]
    [StringLength(50, MinimumLength = 3)]
    public string Username { get; set; } = string.Empty;

    [Required]
    [EmailAddress]
    [JsonPropertyName("email_address")]
    public string Email { get; set; } = string.Empty;

    [MinLength(12)]
    [MaxLength(128)]
    [JsonIgnore]
    public string Password { get; set; } = string.Empty;

    [Range(13, 120)]
    public int Age { get; set; }

    [RegularExpression("^[A-Z]{2}$")]
    public string? CountryCode { get; set; }

    [Obsolete]
    public string? LegacyId { get; set; }
}
