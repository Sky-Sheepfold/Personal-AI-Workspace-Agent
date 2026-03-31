package com.sheepfold.personalaiworkspaceagent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TimeAwarenessTool {

    private static final Logger log = LoggerFactory.getLogger(TimeAwarenessTool.class);

    @Tool(name = "get_current_time", description = "获取当前系统时间（格式 yyyy-MM-dd HH:mm:ss，含时区与毫秒时间戳）。")
    public Map<String, Object> getCurrentTime() {
        ZoneId zoneId = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zoneId).truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> result = new LinkedHashMap<>();
        String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        result.put("time", formatted);
        result.put("timezone", zoneId.getId());
        result.put("epochMillis", Instant.from(now).toEpochMilli());
        log.info("时间感知器返回时间: {}, timezone={}", formatted, zoneId.getId());
        return result;
    }
}
