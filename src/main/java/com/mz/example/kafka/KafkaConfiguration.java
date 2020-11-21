package com.mz.example.kafka;

import com.mz.example.neo4j.Neo4jService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
public class KafkaConfiguration implements CommandLineRunner {

    public static final String BROKER = "localhost:9092";
    public static final String CONSUMER_GROUP = "counsumer_group";
    public static final String TOPIC = "kafka_topic";

    public static final String NEO4J_SERVICE_PROPERTY = "neo4j.service";

    @Autowired
    private KafkaReader kafkaReader;

    private Map<String, Object> kafkaConsumerProperties(Neo4jService neo4jService) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, CONSUMER_GROUP);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, (int) Duration.ofSeconds(120).toMillis());

        //Custom partition assignment
        properties.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RegionalAssignor.class.getName());
        properties.put(KafkaConfiguration.NEO4J_SERVICE_PROPERTY, neo4jService);
        return properties;
    }

    @Bean
    @Autowired
    public KafkaConsumer<byte[], byte[]> kafkaConsumer(Neo4jService neo4jService) {
        return new KafkaConsumer<>(kafkaConsumerProperties(neo4jService));
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("Will start kafka polling loop now");
        kafkaReader.startConsumingMessages();
    }
}
