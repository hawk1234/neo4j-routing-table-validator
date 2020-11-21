package com.mz.example.neo4j;

import lombok.Data;

import java.util.List;
import java.util.Optional;

@Data
public class Neo4jRoutingTable {

    public static final String WRITE_ROLE = "WRITE";
    public static final String READ_ROLE = "READ";
    public static final String ROUTE_ROLE = "ROUTE";

    private int ttl;
    private List<Server> servers;

    @Data
    public static class Server{
        private List<String> addresses;
        private String role;

        public boolean isLeader() {
            return WRITE_ROLE.equals(role);
        }
    }

    public Optional<String> getLeaderAddress() {
        return servers.stream()
                .filter(Server::isLeader)
                .findFirst()
                .flatMap(server -> server.getAddresses().stream().findFirst());
    }
}
