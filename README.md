Simple Spring Boot app to verify Neo4j load balancing policies
==========

Neo4j load balancing policy allows to restrict with which server within the cluster client can communicate to perform 
read operations, eg.: when servers are in placed in different regions of the world, to reduce latency - 
https://neo4j.com/docs/operations-manual/current/clustering-advanced/multi-data-center/load-balancing/#multi-dc-load-balancing-the-load-balancing-framework

Ref for creating local neo4j cluster: https://neo4j.com/docs/operations-manual/current/tutorial/local-causal-cluster/

**NOTE**: this only affect readers, writer will be always returned regardless of policy used

Cypher to get routing table:
```
CALL dbms.cluster.routing.getRoutingTable({})
```
Cypher to get routing table for given policy:
```
CALL dbms.cluster.routing.getRoutingTable({policy:"mypolicy"})
```

Debug
----------
```bash
-Xdebug -Xrunjdwp:server=y,transport=dt_socket,address=5005,suspend=y
```