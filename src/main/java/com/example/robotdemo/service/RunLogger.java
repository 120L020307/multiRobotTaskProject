package com.example.robotdemo.service;

import com.example.robotdemo.model.Models.RunLogEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

public class RunLogger {
    private final String runId;
    private final Logger logger;
    private final ObjectMapper mapper;
    private final Consumer<RunLogEntry> sink;
    private final long startedAt = System.currentTimeMillis();
    private final List<RunLogEntry> entries = new ArrayList<>();
    private int sequence = 0;

    public RunLogger(String runId, Logger logger, ObjectMapper mapper, Consumer<RunLogEntry> sink) {
        this.runId = runId;
        this.logger = logger;
        this.mapper = mapper;
        this.sink = sink;
    }

    public static RunLogger buffered(String runId, Logger logger, ObjectMapper mapper) {
        return new RunLogger(runId, logger, mapper, null);
    }

    public void input(String stage, String title, Object payload) {
        emit(stage, "input", title, payload);
    }

    public void output(String stage, String title, Object payload) {
        emit(stage, "output", title, payload);
    }

    public void info(String stage, String title, Object payload) {
        emit(stage, "info", title, payload);
    }

    public List<RunLogEntry> entries() {
        return List.copyOf(entries);
    }

    private void emit(String stage, String direction, String title, Object payload) {
        RunLogEntry entry = new RunLogEntry(
                runId,
                ++sequence,
                stage,
                direction,
                title,
                payload,
                System.currentTimeMillis() - startedAt,
                Instant.now().toString()
        );
        entries.add(entry);
        logger.info("[{}][{}][{}][{}] {} payload={}", runId, entry.step(), stage, direction, title, summarize(payload));
        if (sink != null) sink.accept(entry);
    }

    private String summarize(Object payload) {
        try {
            String json = mapper.writeValueAsString(payload);
            return json.length() <= 2400 ? json : json.substring(0, 2400) + "...(truncated)";
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }
}
