package com.notifyhub.template;

import com.notifyhub.exception.TemplateNotFoundException;
import com.notifyhub.notification.NotificationChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateEngine {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    private final TemplateRepository templateRepository;

    public RenderedTemplate render(String templateId,
                                   NotificationChannel channel,
                                   Map<String, Object> templateData) {
        Template template = templateRepository.findByTemplateIdAndChannel(templateId, channel)
                .orElseThrow(() -> new TemplateNotFoundException(
                        String.format("Template not found: id=%s channel=%s", templateId, channel)));

        Map<String, Object> data = templateData == null ? Map.of() : templateData;
        String body = substitute(template.getBody(), data);
        String subject = template.getSubject() == null ? null : substitute(template.getSubject(), data);
        return new RenderedTemplate(subject, body);
    }

    private String substitute(String text, Map<String, Object> data) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = data.get(key);
            String replacement = value == null ? matcher.group(0) : value.toString();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public record RenderedTemplate(String subject, String body) { }
}
