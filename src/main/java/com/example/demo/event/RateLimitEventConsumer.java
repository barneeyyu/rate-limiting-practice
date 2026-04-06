package com.example.demo.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;

@Component
public class RateLimitEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RateLimitEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final String nameServer;
    private final String topic;
    private DefaultMQPushConsumer consumer;

    public RateLimitEventConsumer(
            ObjectMapper objectMapper,
            @Value("${rocketmq.name-server}") String nameServer,
            @Value("${rocketmq.producer.topic}") String topic) {
        this.objectMapper = objectMapper;
        this.nameServer = nameServer;
        this.topic = topic;
    }

    @PostConstruct
    public void start() throws Exception {
        consumer = new DefaultMQPushConsumer("rate-limit-consumer-group");
        consumer.setNamesrvAddr(nameServer);
        consumer.subscribe(topic, "*");

        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (MessageExt msg : messages) {
                try {
                    String json = new String(msg.getBody(), StandardCharsets.UTF_8);
                    RateLimitEvent event = objectMapper.readValue(json, RateLimitEvent.class);
                    log.info("Received event: {} for apiKey: {}", event.eventType(), event.apiKey());
                } catch (Exception e) {
                    log.error("Failed to process message", e);
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });

        consumer.start();
        log.info("RateLimitEventConsumer started");
    }

    @PreDestroy
    public void stop() {
        if (consumer != null) {
            consumer.shutdown();
            log.info("RateLimitEventConsumer stopped");
        }
    }
}
