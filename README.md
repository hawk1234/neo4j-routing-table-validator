Neo4j Routing Table Demo
==========

Prerequisites
----------
- Java 1.8 to run this app
- Download neo4j and configure local cluster. You can refer to example configuration in ./cluster-conf - for more links 
see section *Reading from Neo4j*
- [OPTIONAL] Download kafka to run example of partition assignor that will assign consumers based on current 
neo4j leader with write role.

Some useful commands for running kafka server:
```bash
start zookeeper-server-start.bat ..\..\config\zookeeper.properties
start kafka-server-start.bat ..\..\config\server.properties
kafka-topics.bat --bootstrap-server localhost:9092 --topic kafka_topic --create --partitions 4 --replication-factor 1
kafka-topics.bat --bootstrap-server localhost:9092 --list
kafka-topics.bat --bootstrap-server localhost:9092 --topic kafka_topic --describe
```

Debug props
----------
```bash
-Xdebug -Xrunjdwp:server=y,transport=dt_socket,address=5005,suspend=y
```

Reading from Neo4j
----------

Neo4j load balancing policy allows to restrict with which server within the cluster client can communicate to perform 
read operations, eg.: when servers are placed in different regions of the world, to reduce latency - 
https://neo4j.com/docs/operations-manual/current/clustering-advanced/multi-data-center/load-balancing/#multi-dc-load-balancing-the-load-balancing-framework

Ref for creating local neo4j cluster: https://neo4j.com/docs/operations-manual/current/tutorial/local-causal-cluster/

Demo cluster configuration can be found ./cluster-conf. This configures cluster with 6 servers. Simulating 3 servers within 
each of 2 regions. When connecting to Neo4j cluster you can specify policy which determines to which servers queries will be routed. 
`causal_clustering.load_balancing.config.server_policies.mypolicy` is configured to first rout to `region-1` servers and 
then if unavailable to `region-2` servers.

**NOTE**: this only affect readers, writer will be always returned regardless of policy used

Cypher to get routing table:
```
CALL dbms.cluster.routing.getRoutingTable({})
```
Cypher to get routing table for given policy:
```
CALL dbms.cluster.routing.getRoutingTable({policy:"mypolicy"})
```

Write to Neo4j - Custom kafka partition assignment strategy
----------

In case of write queries operations are always routed to neo4j server with write role. There is one such server in the cluster at a 
given time. This example shows how to assign partitions based on which server is current neo4j cluster leader.

#### 1. What is kafka rebalance process

Kafka rebalance process takes topics subscribed by consumers and assigns its partitions among those consumers. Only
one consumer can be assigned to given partition at any time. When assigned consumer can start consuming messages from
assigned partitions.

#### 2. What triggers rebalance process

- whenever consumer leaves or joins the group
- whenever consumer is considered dead by kafka cluster. Consumer is considered dead if:
  - Consumer will not send heartbeat within `session.timeout.ms`
  - Consumer maximum time between subsequent polls is exceeded - `max.poll.inteval.ms`
  
#### 3. How does rebalance work

Article on kafka rebalance process - https://medium.com/streamthoughts/apache-kafka-rebalance-protocol-or-the-magic-behind-your-streams-applications-e94baf68e4f2
Starting from kafka 2.3 there are two rebalance protocols *Eager* and *Cooperative*. Article focuses on Eager assignment.
Eager assignment works globally on all consumers within a group. Frequent rebalances may lead to decreased performance.
Cooperative assignment aims to reduce the problem by doing assignment incrementally on consumers. Implementation in this 
project is Eager. Kafka rebalance process steps:

- Consumers send JoinGroup request to their group coordinator on kafka server. This information contains
  - List of topics consumer wants to subscribe to
  - Metadata refered to as user data. This can be any information written in a form of `java.nio.ByteBuffer`
  - List of partition assignment strategies supported by this consumer - `partition.assignment.strategy`
- Coordinator waits `group.initial.rebalance.delay.ms` - **this is coordinator/broker configuration** - for other 
consumers joining the group. If new consumer joins during that time go back to point 1 (new joiner resets the clock) 
else proceed.
- Coordinator elects strategy supported by all consumers and chooses consumer that will be responsible for assignment
- Coordinator send information to consumer leading the assignment:
  - List of all consumers for the assignment. This information contains member id; topics; user data for each consumer
  - Information about number of partitions for each topic.
- Consumer replays back with the assignment for each consumer
- Coordinator notifies all consumers. Consumers can start polling messages and partition assignment listeners get notified

#### 4. Available strategies

Base Eager strategies are:
- RangeAssignor - default
- RoundRobinAssignor
- StickyAssignor

You can read about this strategies in this article https://medium.com/streamthoughts/understanding-kafka-partition-assignment-strategies-and-how-to-write-your-own-custom-assignor-ebeda1fc06f3
There are more strategies in newer kafka versions > 2.3

#### 5. How to change assignment strategy for existing deployment

If new consumer tries to join the group with strategy not supported by other consumers coordinator will not allow it.
Property `partition.assignment.strategy` specifies list of strategies that can be used during the assignment. Coordinator 
selects first strategy in the list supported by all consumers. If you want to switch strategy without turing off all
consumers in the group you must place new strategy as the first element on the list while keeping current strategy.
Strategy will be switched after all consumers will be restarted.

#### 6. What is static membership

Static membership is a concept that prevents kafka from triggering rebalance immediately after one of consumers is 
**considered dead** - assumes consumer failure. In such case consumer have `session.timeout.ms` time to rejoin the group
and will be assigned same partitions without triggering rebalance of the whole group. This functionality is enabled by
`group.instance.id` property.

#### 7. Implementing custom partition assignment strategy

Kafka has very good javadoc on the topic + example implementation you can find in this project `com.mz.example.kafka.RegionalAssignor`.
But I want to point out few things.

##### 7.1. Kafka internal vs public API

Internal kafka API resides in package `org.apache.kafka.clients.consumer.internals`. This API can be modified between 
kafka versions. You should not extend internal APIs in your project. Assignment strategy is implemented by:

older versions < 2.0 (I'm not sure couldn't find it)
```java
import org.apache.kafka.clients.consumer.internals.PartitionAssignor;
```

new versions > 2.0
```java
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor;
```

**NOTE:** that this interface belonged to internal API, but never should have. So even in newer versions this interface 
is present. Kafka says that it was suppose to be public from beginning. However some other internal implementations should 
be extended with caution eg.: `org.apache.kafka.clients.consumer.internals.AbstractPartitionAssignor` all base strategies 
inherit from this one.
  
##### 7.2. Sending subscriber data to assignment leader

Sometimes you may want to send some consumer specific data assignment leader. In my case I needed to send neo4j server 
urls allowed to be writers by given consumer in order to assign this consumer to the partition.

https://kafka.apache.org/24/javadoc/org/apache/kafka/clients/consumer/ConsumerPartitionAssignor.html#subscriptionUserData-java.util.Set-

##### 7.3. Injecting other objects into our partition assignor

You do not instantiate partition assignor by yourself. Instead kafka does it using reflection, so assignor implementation
needs to have empty public constructor. However in some cases you may want to inject some other services into your assignor.
You can do that by implementing:

https://kafka.apache.org/20/javadoc/index.html?org/apache/kafka/common/Configurable.html

In such case after instantiating assignor, kafka will pass `java.util.Map` containing consumer properties to this method 
and you can read configs you need including some service objects.

**NOTE:** Assignors are created and configured during creation of consumer (invocation of constructor). So whenever 
configuration takes long time or throws exception it will affect consumer creation.

#### 8. What happens when partition assignment takes long time

If partition assignment exceeds `session.timeout.ms` consumer will be throw out from the group and coordinator will elect
new partition assignment leader. In such case most likely consumer will try to rejoin the group as soon as long operation 
finishes and this will trigger next rebalance. So this configuration should be properly adjusted and assignment shouldn't
take too long.

#### 9. What happens when exception occurs during partition assignment

Assignment is executed in same thread as poll is called, so exception during assignment may cause you processing thread 
to fail if not properly handled.