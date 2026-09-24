package reports.service;

public interface ReportService {
  void warmUp();

  String render(int rows);

  int count();
}
