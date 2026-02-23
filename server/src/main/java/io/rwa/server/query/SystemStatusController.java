package io.rwa.server.query;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class SystemStatusController {

    private final IndexerStatusService indexerStatusService;

    public SystemStatusController(IndexerStatusService indexerStatusService) {
        this.indexerStatusService = indexerStatusService;
    }

    @GetMapping("/admin/indexer/status")
    public Map<String, Object> indexerStatus() {
        return indexerStatusService.indexerStatus();
    }

    @GetMapping("/system/data-freshness")
    public Map<String, Object> dataFreshness() {
        return indexerStatusService.dataFreshness();
    }
}
