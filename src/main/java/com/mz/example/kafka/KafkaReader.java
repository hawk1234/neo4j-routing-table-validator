package com.mz.example.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collections;

@Slf4j
@Component
public class KafkaReader {

    @Autowired
    private KafkaConsumer<byte[], byte[]> kafkaConsumer;
    private boolean terminated = false;

    @PostConstruct
    public void setup() {
        kafkaConsumer.subscribe(Collections.singletonList(KafkaConfiguration.TOPIC));
    }

    @PreDestroy
    //TODO: no graceful shutdown
    public synchronized void terminate() {
        terminated = true;
        kafkaConsumer.close();
    }

    private synchronized boolean isTerminated() {
        return terminated;
    }

    @Async
    public void startConsumingMessages() {
        while (!isTerminated()) {
            ConsumerRecords<byte[], byte[]> records = kafkaConsumer.poll(Duration.ofSeconds(20));
            log.info("Polled " + records.count() + " records");
            log.info("===================================== PARTITION ASSIGNMENT =========================================");
            kafkaConsumer.assignment().forEach(tp -> log.info("assigned to partition: " + tp.toString()));
            log.info("===================================== END OF ASSIGNMENT =========================================");
        }
    }
}
