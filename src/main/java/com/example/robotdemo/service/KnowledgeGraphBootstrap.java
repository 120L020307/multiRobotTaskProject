package com.example.robotdemo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeGraphBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphBootstrap.class);

    private final KnowledgeGraphSyncService syncService;
    private final DataLoader dataLoader;
    private final boolean syncOnStartup;

    public KnowledgeGraphBootstrap(
            KnowledgeGraphSyncService syncService,
            DataLoader dataLoader,
            @Value("${kg.sync-on-startup:true}") boolean syncOnStartup
    ) {
        this.syncService = syncService;
        this.dataLoader = dataLoader;
        this.syncOnStartup = syncOnStartup;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!syncOnStartup) {
            log.info("Knowledge graph static-resource bootstrap is disabled");
            return;
        }

        try {
            KnowledgeGraphSyncService.ResourceSyncResult result =
                    syncService.syncStaticResources(
                            dataLoader.contracts(),
                            dataLoader.virtualRobotSensors()
                    );
            log.info(
                    "Knowledge graph resources synchronized: robots={}, capabilities={}, contracts={}, areas={}, sensors={}",
                    result.robotCount(),
                    result.capabilityCount(),
                    result.contractCount(),
                    result.areaCount(),
                    result.sensorCount()
            );
        } catch (Exception exception) {
            log.warn(
                    "Knowledge graph bootstrap failed open; the application will continue without blocking: {}",
                    exception.getMessage()
            );
        }
    }
}
