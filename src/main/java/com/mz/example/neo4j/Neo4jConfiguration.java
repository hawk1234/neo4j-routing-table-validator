package com.mz.example.neo4j;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.*;
import org.neo4j.driver.internal.InternalDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;
import java.net.URI;
import java.util.Arrays;
import java.util.Optional;

@Slf4j
@Configuration
public class Neo4jConfiguration {

    private static final String NEO4J_LB_POLICY = "mypolicy";
    private static final String NEO4J_USER = "neo4j";
    private static final String NEO4J_PASSWORD = "p4ssword";
    private static final String NEO4J_PROTOCOL = "neo4j://";
    public static final String NEO4J_DATABASE_NAME = "neo4j";

    @Autowired
    private Driver neo4jDriver;
    @Autowired
    private Session neo4jSession;

    @PreDestroy
    public void dispose() {
        log.info("Disposing neo4j components");
        Optional.ofNullable(neo4jSession).ifPresent(session -> {
            log.info("Closing neo4j session");
            session.close();
            log.info("Done closing");
        });
        Optional.ofNullable(neo4jDriver).ifPresent(driver -> {
            log.info("Closing neo4j driver");
            driver.close();
            log.info("Done closing");
        });
        log.info("Done disposing");
    }

    @Bean
    public InternalDriver neo4jDriver() {
        final String policyURLParam = getPolicyURLParam(NEO4J_LB_POLICY);

        AuthToken authToken = AuthTokens.basic(NEO4J_USER, NEO4J_PASSWORD);
        Iterable<URI> uris = Arrays.asList(
            URI.create(NEO4J_PROTOCOL+"localhost:7685"+policyURLParam),//r1-node-1
            URI.create(NEO4J_PROTOCOL+"localhost:7686"+policyURLParam),//r1-node-2
            URI.create(NEO4J_PROTOCOL+"localhost:7687"+policyURLParam),//r1-node-3
            URI.create(NEO4J_PROTOCOL+"localhost:7688"+policyURLParam),//r2-node-1
            URI.create(NEO4J_PROTOCOL+"localhost:7689"+policyURLParam),//r2-node-2
            URI.create(NEO4J_PROTOCOL+"localhost:7690"+policyURLParam)//r2-node-3
        );

        return (InternalDriver) GraphDatabase.routingDriver(
                uris, authToken, Config.defaultConfig());
    }

    @Bean
    @Autowired
    public Session neo4jSession(Driver neo4jDriver) {
        return neo4jDriver.session(SessionConfig.forDatabase(NEO4J_DATABASE_NAME));
    }

    @SuppressWarnings("all")
    private String getPolicyURLParam(String policy) {
        return policy.isEmpty() ? "" : ("?policy=" + policy);
    }
}
