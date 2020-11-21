package com.mz.example.neo4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/api")
public class Neo4jController {

    @Autowired
    private Neo4jService neo4jService;
    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping("/routing-table")
    public String handleViewCurrentRoutingTable() throws JsonProcessingException {
//        return neo4jService.unsafeGetCurrentRoutingTableViaReflection();
        return objectMapper.writeValueAsString(neo4jService.getCurrentRoutingTableViaNeo4jQuery());
    }

    @GetMapping("/routing-table/expiry-time")
    public LocalDateTime handleGetRoutingTableExpiryTime() {
        return neo4jService.unsafeGetRoutingTableExpiryTimeViaReflection();
    }

    @GetMapping("/routing-table/expire-now")
    public LocalDateTime handleExpireCurrentRoutingTable() {
        return neo4jService.unsafeExpireCurrentRoutingTableViaReflection();
    }

    @GetMapping("/do-write")
    public void handleSimpleWrite() {
        neo4jService.simpleWrite();
    }

    @GetMapping("/do-read")
    public String handleSimpleRead() {
        return neo4jService.simpleRead();
    }
}
