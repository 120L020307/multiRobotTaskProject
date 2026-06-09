package com.example.robotdemo.llm;

import com.example.robotdemo.config.LlmProperties;
import com.example.robotdemo.model.Models.LlmTrace;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component
public class DeepSeekClient {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final LlmProperties properties;
    private final ObjectMapper mapper;
    private final RestTemplate restTemplate = new RestTemplate();

    public DeepSeekClient(LlmProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public AgentResult callJsonAgent(String agentName, String systemPrompt, String userPrompt, String schemaHint) {
        String apiKey = properties.deepseek() == null ? null : properties.deepseek().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY 未配置。请通过环境变量设置后重启 Java 后端。");
        }
        long started = System.currentTimeMillis();
        String baseUrl = Optional.ofNullable(properties.deepseek().baseUrl()).orElse("https://api.deepseek.com").replaceAll("/$", "");
        String model = Optional.ofNullable(properties.deepseek().model()).orElse("deepseek-v4-flash");
        String system = systemPrompt + "\n\n你必须只输出合法 JSON 对象，不要输出 Markdown、解释文字或代码块。\nJSON 结构要求：\n" + schemaHint;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        List<Exception> failures = new ArrayList<>();
        for (int attempt = 1; attempt <= 3; attempt++) {
            boolean strictJsonMode = attempt == 1;
            Map<String, Object> body = buildRequestBody(model, system, userPrompt, strictJsonMode, attempt);
            try {
                log.info("DeepSeek request agent={} attempt={} strict_json={} model={} prompt_chars={}", agentName, attempt, strictJsonMode, model, userPrompt.length());
                ResponseEntity<String> response = restTemplate.exchange(
                        baseUrl + "/chat/completions",
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        String.class
                );
                JsonNode root = mapper.readTree(response.getBody());
                JsonNode choice = root.path("choices").path(0);
                JsonNode message = choice.path("message");
                String content = firstNonBlank(
                        message.path("content").asText(null),
                        message.path("reasoning_content").asText(null)
                );
                String finishReason = choice.path("finish_reason").asText(null);
                log.info("DeepSeek response agent={} attempt={} status={} finish_reason={} content_chars={} usage={}",
                        agentName, attempt, response.getStatusCode().value(), finishReason, content == null ? 0 : content.length(), root.path("usage"));
                if (content == null || content.isBlank()) {
                    throw new IllegalStateException("DeepSeek 返回空 content，finish_reason=" + finishReason + "，raw=" + abbreviate(response.getBody(), 1800));
                }
                String json = extractJson(content);
                if (json.isBlank() || !json.trim().startsWith("{")) {
                    throw new IllegalStateException("DeepSeek 返回内容中未找到 JSON 对象，content=" + abbreviate(content, 1800));
                }
                Map<String, Object> data = mapper.readValue(json, new TypeReference<>() {});
                Map<String, Object> usage = mapper.convertValue(root.path("usage"), new TypeReference<>() {});
                LlmTrace trace = new LlmTrace(agentName, "deepseek_chat_completions_api", model, System.currentTimeMillis() - started, root.path("id").asText(null), usage);
                return new AgentResult(data, trace);
            } catch (RestClientException e) {
                failures.add(e);
                log.warn("DeepSeek API call failed agent={} attempt={} message={}", agentName, attempt, e.getMessage());
            } catch (Exception e) {
                failures.add(e);
                log.warn("DeepSeek parse failed agent={} attempt={} message={}", agentName, attempt, e.getMessage());
            }
        }
        Exception last = failures.isEmpty() ? new IllegalStateException("unknown") : failures.get(failures.size() - 1);
        throw new IllegalStateException("DeepSeek 响应解析失败，已重试 3 次；最后错误：" + last.getMessage(), last);
    }

    private Map<String, Object> buildRequestBody(String model, String system, String userPrompt, boolean strictJsonMode, int attempt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", userPrompt + "\n\n只返回一个 JSON 对象。不要解释，不要 Markdown，不要代码块。第 " + attempt + " 次生成。")
        ));
        if (strictJsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        body.put("temperature", 0.1);
        body.put("max_tokens", 3072);
        body.put("stream", false);
        return body;
    }

    private String extractJson(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```json")) trimmed = trimmed.substring(7).trim();
        if (trimmed.startsWith("```")) trimmed = trimmed.substring(3).trim();
        if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return trimmed;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) return "null";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...(truncated)";
    }

    public record AgentResult(Map<String, Object> data, LlmTrace trace) {}
}
