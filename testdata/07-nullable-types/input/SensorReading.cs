namespace Telemetry.Dtos;

public class SensorReadingDto
{
    public Guid Id { get; set; } = Guid.NewGuid();

    public int? Floor { get; set; }

    public double Value { get; set; }

    public double? Calibrated { get; set; }

    public DateTime RecordedAt { get; set; } = DateTime.UtcNow;

    public DateTimeOffset? AcknowledgedAt { get; set; }

    public TimeSpan Window { get; set; }

    public string? Label { get; set; }

    public byte Channel { get; set; }

    public uint Sequence { get; set; }

    public List<int> Samples { get; set; } = new();

    public Dictionary<string, double?> Tags { get; set; } = new Dictionary<string, double?>();

    public bool IsAlert => Value > 100;
}
