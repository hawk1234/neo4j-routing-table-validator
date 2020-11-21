package com.mz.example.kafka;

import com.mz.example.neo4j.Neo4jService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor;
import org.apache.kafka.clients.consumer.RangeAssignor;
import org.apache.kafka.common.Configurable;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.protocol.types.*;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class RegionalAssignor extends RangeAssignor implements ConsumerPartitionAssignor, Configurable {

    private static final String REGIONAL_ASSIGNOR_NAME = "regional-assignor";
    private static final String ELIGIBLE_NEO4J_LEADERS_KEY_NAME = "eligible-neo4j-leaders";
    private static final Schema REGIONAL_ASSIGNOR_USER_DATA = new Schema(
            new Field(ELIGIBLE_NEO4J_LEADERS_KEY_NAME, new ArrayOf(Type.STRING))
    );


    private Neo4jService neo4jService;
    private ByteBuffer buffer;

    /**
     * Is called on Kafka consumer creation (constructor).
     *
     * @param configs properties passed to Kafka consumer
     */
    @Override
    public void configure(Map<String, ?> configs) {
        log.info("Configuring RegionalAssignor instance");
        if(!configs.containsKey(KafkaConfiguration.NEO4J_SERVICE_PROPERTY)) {
            throw new IllegalArgumentException("Assignor can't work. Missing configuration for "
                    + KafkaConfiguration.NEO4J_SERVICE_PROPERTY);
        }
        this.neo4jService = retrieveNeo4jService(configs);
    }

    private Neo4jService retrieveNeo4jService(Map<String, ?> configs) {
        Object neo4jService = configs.get(KafkaConfiguration.NEO4J_SERVICE_PROPERTY);
        if(!(neo4jService instanceof Neo4jService)) {
            throw new IllegalArgumentException("Provided "
                    + KafkaConfiguration.NEO4J_SERVICE_PROPERTY + " is not an instance of "
                    + Neo4jService.class.getName());
        }
        return (Neo4jService) neo4jService;
    }

    @Override
    public String name() {
        return REGIONAL_ASSIGNOR_NAME;
    }

    @Override
    public ByteBuffer subscriptionUserData(Set<String> topics) {
        return serializeConsumerData(neo4jService.getSameDCServers());
    }

    private Map<String, Subscription> restrictToConsumersWithinSameDataCenterAsNeo4jLeader(Map<String, Subscription> subscriptions) {
        Optional<String> neo4jLeader = neo4jService.getCurrentRoutingTableViaNeo4jQuery().getLeaderAddress();
        Map<String, Subscription> restricted;
        if (neo4jLeader.isPresent()) {
            restricted = subscriptions.entrySet().stream().filter(memberSub -> {
                List<String> eligibleNeo4jLeaders = deserializeConsumerData(memberSub.getValue().userData());
                return eligibleNeo4jLeaders.contains(neo4jLeader.get());
            }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        } else {
            log.info("No neo4j leader found. Doing assignment on all consumers.");
            restricted = subscriptions;
        }

        Map<String, Subscription> ret = restricted;
        if(restricted.isEmpty()) {
            log.info("No consumers in same data center as neo4j leader found. Doing assignment on all consumers.");
            ret = subscriptions;
        }
        return ret;
    }

    @Override
    public Map<String, List<TopicPartition>> assign(Map<String, Integer> partitionsPerTopic, Map<String, Subscription> subscriptions) {
        long time = System.currentTimeMillis();
        log.info("Running " + name() + " partition assignment.");
        Map<String, List<TopicPartition>> ret = new HashMap<>(super.assign(partitionsPerTopic,
                restrictToConsumersWithinSameDataCenterAsNeo4jLeader(subscriptions)));
        subscriptions.forEach((memberId, subscription) -> ret.putIfAbsent(memberId, Collections.emptyList()));
        time = System.currentTimeMillis() - time;
        log.info("Finished partition assignment took: " + time + " ms.");
        return ret;
    }

    private ByteBuffer serializeConsumerData(List<String> eligibleNeo4jLeaders) {
        Struct struct = new Struct(REGIONAL_ASSIGNOR_USER_DATA);
        struct.set(ELIGIBLE_NEO4J_LEADERS_KEY_NAME, eligibleNeo4jLeaders.toArray());
        ByteBuffer buffer = ByteBuffer.allocate(REGIONAL_ASSIGNOR_USER_DATA.sizeOf(struct));
        REGIONAL_ASSIGNOR_USER_DATA.write(buffer, struct);
        buffer.flip();
        return buffer;
    }

    private List<String> deserializeConsumerData(ByteBuffer consumerData) {
        try {
            Struct struct = REGIONAL_ASSIGNOR_USER_DATA.read(consumerData);
            return Arrays.stream(struct.getArray(ELIGIBLE_NEO4J_LEADERS_KEY_NAME))
                    .map(el -> (String) el).collect(Collectors.toList());
        } catch (Throwable ex) {
            log.error("Error deserializing consumer data during partition assignment.", ex);
            return Collections.emptyList();
        }
    }

    //<editor-fold desc="NOT USED - but you can try it out">
    /**
     * If consumer will not make assignment within session.timeout.ms it will be removed from the group.
     * Another consumer (if present) will be making assignment. After this consumer finishes it will rejoin the group
     * and trigger rebalance again. If this happens too often will impact performance.
     */
    private void testHowSlowMethodAffectsAssignment() {
        log.info("ASSIGNMENT: before sleep");
        try {
            Thread.sleep(Duration.ofSeconds(60).toMillis());
        } catch (InterruptedException ex) {
            throw new RuntimeException("Sleep was interrupted", ex);
        }
        log.info("ASSIGNMENT: after sleep");
    }

    /**
     * Assignment is done in same thread as poll, when it is required. Kafka does not handle this exception
     */
    private void testHowThrowExceptionAffectsAssignment() {
        log.info("ASSIGNMENT: before short sleep");
        try {
            //sleep a bit to be able to track how error is handled
            Thread.sleep(Duration.ofSeconds(10).toMillis());
        } catch (InterruptedException ex) {
            throw new RuntimeException("Sleep was interrupted", ex);
        }
        log.info("ASSIGNMENT: after short sleep");
        log.info("ASSIGNMENT: throwing exception");
        throw new RuntimeException("Exception throw during partition assignment");
    }
    //</editor-fold>
}
