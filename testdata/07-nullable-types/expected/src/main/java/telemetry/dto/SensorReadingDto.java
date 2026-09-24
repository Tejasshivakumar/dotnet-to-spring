package telemetry.dto;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SensorReadingDto {
  private UUID id = UUID.randomUUID();

  private Integer floor;

  private double value;

  private Double calibrated;

  private LocalDateTime recordedAt = LocalDateTime.now(ZoneOffset.UTC);

  private OffsetDateTime acknowledgedAt;

  private Duration window;

  private String label;

  private byte channel;

  private long sequence;

  private List<Integer> samples = new ArrayList<>();

  private Map<String, Double> tags = new HashMap<>();

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public Integer getFloor() {
    return floor;
  }

  public void setFloor(Integer floor) {
    this.floor = floor;
  }

  public double getValue() {
    return value;
  }

  public void setValue(double value) {
    this.value = value;
  }

  public Double getCalibrated() {
    return calibrated;
  }

  public void setCalibrated(Double calibrated) {
    this.calibrated = calibrated;
  }

  public LocalDateTime getRecordedAt() {
    return recordedAt;
  }

  public void setRecordedAt(LocalDateTime recordedAt) {
    this.recordedAt = recordedAt;
  }

  public OffsetDateTime getAcknowledgedAt() {
    return acknowledgedAt;
  }

  public void setAcknowledgedAt(OffsetDateTime acknowledgedAt) {
    this.acknowledgedAt = acknowledgedAt;
  }

  public Duration getWindow() {
    return window;
  }

  public void setWindow(Duration window) {
    this.window = window;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(String label) {
    this.label = label;
  }

  public byte getChannel() {
    return channel;
  }

  public void setChannel(byte channel) {
    this.channel = channel;
  }

  public long getSequence() {
    return sequence;
  }

  public void setSequence(long sequence) {
    this.sequence = sequence;
  }

  public List<Integer> getSamples() {
    return samples;
  }

  public void setSamples(List<Integer> samples) {
    this.samples = samples;
  }

  public Map<String, Double> getTags() {
    return tags;
  }

  public void setTags(Map<String, Double> tags) {
    this.tags = tags;
  }

  public boolean isIsAlert() {
    return value > 100;
  }
}
