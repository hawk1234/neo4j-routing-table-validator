package com.mz.example.neo4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.internal.DatabaseNameUtil;
import org.neo4j.driver.internal.InternalDriver;
import org.neo4j.driver.internal.SessionFactoryImpl;
import org.neo4j.driver.internal.cluster.ClusterRoutingTable;
import org.neo4j.driver.internal.cluster.RoutingTable;
import org.neo4j.driver.internal.cluster.RoutingTableHandler;
import org.neo4j.driver.internal.cluster.RoutingTableRegistryImpl;
import org.neo4j.driver.internal.cluster.loadbalancing.LoadBalancer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class Neo4jService {

    @Autowired
    private InternalDriver neo4jDriver;
    @Autowired
    private Session session;
    @Autowired
    private ObjectMapper objectMapper;
    @Getter
    @Value("${neo4j.sameDCServers}")
    private List<String> sameDCServers;

    public String unsafeGetCurrentRoutingTableViaReflection() {
        return getRoutingTable()
                .map(RoutingTable::toString)
                .orElse("Can't retrieve routing table information");
    }

    public LocalDateTime unsafeGetRoutingTableExpiryTimeViaReflection() {
        return getRoutingTable()
                .flatMap(rt -> getRoutingTableExpiryTime(rt, false))
                .orElse(null);
    }

    public LocalDateTime unsafeExpireCurrentRoutingTableViaReflection() {
        return getRoutingTable()
                .flatMap(rt -> getRoutingTableExpiryTime(rt, true))
                .orElse(null);
    }

    public void simpleWrite() {
        session.run("MERGE (p:Person {id: 1})");
    }

    public String simpleRead() {
        return session.run("MATCH (p:Person {id: 1}) RETURN p").single().toString();
    }

    public Neo4jRoutingTable getCurrentRoutingTableViaNeo4jQuery() {
        Record rtRecord = session.run("CALL dbms.cluster.routing.getRoutingTable({})").single();
        try {
            String asJson = objectMapper.writeValueAsString(rtRecord.asMap());
            return objectMapper.readValue(asJson, Neo4jRoutingTable.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Problem serializing to JSON", e);
        }
    }

    /**
     * NOTE: this works only when you query used DB first so call<br/>
     * {@link #simpleRead()}<br/>
     * or<br/>
     * {@link #simpleWrite()}<br/>
     */
    private Optional<RoutingTable> getRoutingTable() {
        SessionFactoryImpl sessionFactory = (SessionFactoryImpl) neo4jDriver.getSessionFactory();
        LoadBalancer loadBalancer = (LoadBalancer) sessionFactory.getConnectionProvider();

        try {
            Field routingTablesField = LoadBalancer.class.getDeclaredField("routingTables");
            routingTablesField.setAccessible(true);
            RoutingTableRegistryImpl routingTableRegistry = (RoutingTableRegistryImpl)
                    routingTablesField.get(loadBalancer);

            Field routingTableHandlers = RoutingTableRegistryImpl.class.getDeclaredField("routingTableHandlers");
            routingTableHandlers.setAccessible(true);
            Map rtHandlers = (Map) routingTableHandlers.get(routingTableRegistry);

            RoutingTableHandler handler = (RoutingTableHandler) rtHandlers
                    .get(DatabaseNameUtil.database(Neo4jConfiguration.NEO4J_DATABASE_NAME));
            return Optional.of(handler.routingTable());
        } catch (Throwable ex) {
            log.error("Unable to retrieve routing table", ex);
            return Optional.empty();
        }
    }

    private Optional<LocalDateTime> getRoutingTableExpiryTime(RoutingTable routingTable, boolean expireNow) {
        try {
            Field expirationTimestamp = ClusterRoutingTable.class.getDeclaredField("expirationTimestamp");
            expirationTimestamp.setAccessible(true);
            if(expireNow) {
                expirationTimestamp.set(routingTable, System.currentTimeMillis());
            }
            long expiryTime = (long) expirationTimestamp.get(routingTable);
            return Optional.of(Instant.ofEpochMilli(expiryTime).atZone(ZoneId.systemDefault()).toLocalDateTime());
        } catch (Throwable ex) {
            log.error("Can't access routing table expiry time");
            return Optional.empty();
        }
    }
}
