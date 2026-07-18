package com.aihotspot.core.content;

import com.aihotspot.core.api.ApiException;
import com.aihotspot.core.audit.AuditService;
import com.aihotspot.core.auth.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportEditorialService {
    private final ReportEditorialMapper mapper;
    private final PublicReportService reports;
    private final AuditService audit;
    public ReportEditorialService(ReportEditorialMapper mapper, PublicReportService reports, AuditService audit) {
        this.mapper = mapper; this.reports = reports; this.audit = audit;
    }
    public List<ReportEditorialMapper.IssueView> list(int limit) { return mapper.list(Math.min(Math.max(limit, 1), 100)); }
    @Transactional
    public ReportEditorialMapper.IssueView generate(String period, LocalDate anchor, AppUserPrincipal actor, HttpServletRequest request) {
        PublicReportService.ReportView report = reports.generateLive(period, anchor).report();
        UUID id = UUID.fromString(mapper.upsertIssue(UUID.randomUUID(), report.period(), report.startDate(), report.endDate(), report.volume(),
                report.headline(), report.lead(), report.storyCount(), report.eventCount(), report.sourceCount(),
                report.officialSourceCount(), report.featuredCount(), report.estimatedMinutes()));
        mapper.deleteSections(id);
        int sectionOrder = 0;
        for (PublicReportService.Section section : report.sections()) {
            UUID sectionId = UUID.randomUUID();
            mapper.insertSection(sectionId, id, section.code(), section.label(), sectionOrder++);
            int itemOrder = 0;
            for (PublicReportService.ReportItem item : section.items())
                mapper.insertItem(UUID.randomUUID(), sectionId, UUID.fromString(item.id()), itemOrder++);
        }
        audit.record(actor.id(), "REPORT_GENERATED", "REPORT_ISSUE", id, null,
                "{\"period\":\"" + report.period() + "\",\"startDate\":\"" + report.startDate() + "\"}", request);
        return mapper.find(id);
    }
    @Transactional
    public ReportEditorialMapper.IssueView update(UUID id, String headline, String lead, long version,
            AppUserPrincipal actor, HttpServletRequest request) {
        require(id);
        if (mapper.update(id, headline.strip(), lead.strip(), version) != 1) conflict();
        audit.record(actor.id(), "REPORT_EDITED", "REPORT_ISSUE", id, null, "{\"version\":" + version + "}", request);
        return mapper.find(id);
    }
    @Transactional
    public ReportEditorialMapper.IssueView publish(UUID id, long version, AppUserPrincipal actor, HttpServletRequest request) {
        require(id);
        if (mapper.publish(id, actor.id(), version) != 1) conflict();
        audit.record(actor.id(), "REPORT_PUBLISHED", "REPORT_ISSUE", id, null, "{\"version\":" + version + "}", request);
        return mapper.find(id);
    }
    private void require(UUID id) { if (mapper.find(id) == null) throw new ApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "报告不存在"); }
    private void conflict() { throw new ApiException(HttpStatus.CONFLICT, "REPORT_VERSION_CONFLICT", "报告已被其他操作修改，请刷新后重试"); }
}
