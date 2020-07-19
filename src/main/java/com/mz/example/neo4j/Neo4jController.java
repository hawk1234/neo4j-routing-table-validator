package com.mz.example.neo4j;

import lombok.extern.slf4j.Slf4j;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api")
public class Neo4jController {

    @Autowired
    private InternalDriver neo4jDriver;
    @Autowired
    private Session session;

    @GetMapping("/routing-table")
    public String handleViewCurrentRoutingTable() {
        return getRoutingTable()
                .map(RoutingTable::toString)
                .orElse("Can't retrieve routing table information");
    }

    @GetMapping("/routing-table/expiry-time")
    public LocalDateTime handleGetRoutingTableExpiryTime() {
        return getRoutingTable()
                .flatMap(rt -> getRoutingTableExpiryTime(rt, false))
                .orElse(null);
    }

    @GetMapping("/routing-table/expire-now")
    public LocalDateTime handleExpireCurrentRoutingTable() {
        return getRoutingTable()
                .flatMap(rt -> getRoutingTableExpiryTime(rt, true))
                .orElse(null);
    }

    @GetMapping("/do-write")
    public void handleSimpleWrite() {
        session.run("MERGE (p:Person {id: 1})");
    }

    @GetMapping("/do-read")
    public String handleSimpleRead() {
        return session.run("MATCH (p:Person {id: 1}) RETURN p").single().toString();
    }

    /**
     * NOTE: this works only when you query used DB first so call<br/>
     * {@link #handleSimpleRead()}<br/>
     * or<br/>
     * {@link #handleSimpleWrite()}<br/>
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
