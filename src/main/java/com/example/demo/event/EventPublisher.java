package com.example.demo.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final DefaultMQProducer producer;
    private final ObjectMapper objectMapper;
    private final String topic;

    public EventPublisher(
            DefaultMQProducer producer,
            ObjectMapper objectMapper,
            @Value("${rocketmq.producer.topic}") String topic) {
        this.producer = producer;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    public void publish(RateLimitEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            Message message = new Message(topic, json.getBytes(StandardCharsets.UTF_8));
            producer.send(message);
            log.info("Published event: {} for apiKey: {}", event.eventType(), event.apiKey());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event", e);
        } catch (Exception e) {
            log.error("Failed to publish event", e);
        }
    }
}
