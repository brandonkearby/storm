/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.storm.redis.trident;

import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.StormSubmitter;
import backtype.storm.generated.StormTopology;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Values;
import org.apache.storm.redis.trident.mapper.TridentTupleMapper;
import org.apache.storm.redis.trident.state.RedisClusterState;
import org.apache.storm.redis.trident.state.RedisClusterStateQuerier;
import org.apache.storm.redis.trident.state.RedisClusterStateUpdater;
import org.apache.storm.redis.util.config.JedisClusterConfig;
import storm.trident.Stream;
import storm.trident.TridentState;
import storm.trident.TridentTopology;
import storm.trident.testing.FixedBatchSpout;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;

public class WordCountTridentRedisCluster {
    public static StormTopology buildTopology(String redisHostPort){
        Fields fields = new Fields("word", "count");
        FixedBatchSpout spout = new FixedBatchSpout(fields, 4,
                new Values("storm", 1),
                new Values("trident", 1),
                new Values("needs", 1),
                new Values("javadoc", 1)
        );
        spout.setCycle(true);

        Set<InetSocketAddress> nodes = new HashSet<InetSocketAddress>();
        for (String hostPort : redisHostPort.split(",")) {
            String[] host_port = hostPort.split(":");
            nodes.add(new InetSocketAddress(host_port[0], Integer.valueOf(host_port[1])));
        }
        JedisClusterConfig clusterConfig = new JedisClusterConfig.Builder().setNodes(nodes)
                                        .build();
        TridentTupleMapper tupleMapper = new WordCountTupleMapper();
        RedisClusterState.Factory factory = new RedisClusterState.Factory(clusterConfig);

        TridentTopology topology = new TridentTopology();
        Stream stream = topology.newStream("spout1", spout);

        stream.partitionPersist(factory,
                                fields,
                                new RedisClusterStateUpdater("test_", tupleMapper, 86400000),
                                new Fields());

        TridentState state = topology.newStaticState(factory);
        stream = stream.stateQuery(state, new Fields("word"),
                                new RedisClusterStateQuerier("test_", tupleMapper),
                                new Fields("columnName","columnValue"));
        stream.each(new Fields("word","columnValue"), new PrintFunction(), new Fields());
        return topology.build();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Usage: WordCountTrident 0(storm-local)|1(storm-cluster) 127.0.0.1:6379,127.0.0.1:6380");
            System.exit(1);
        }

        Integer flag = Integer.valueOf(args[0]);
        String redisHostPort = args[1];

        Config conf = new Config();
        conf.setMaxSpoutPending(5);
        if (flag == 0) {
            LocalCluster cluster = new LocalCluster();
            cluster.submitTopology("test_wordCounter_for_redis", conf, buildTopology(redisHostPort));
            Thread.sleep(60 * 1000);
            cluster.killTopology("test_wordCounter_for_redis");
            cluster.shutdown();
            System.exit(0);
        } else if(flag == 1) {
            conf.setNumWorkers(3);
            StormSubmitter.submitTopology("test_wordCounter_for_redis", conf, buildTopology(redisHostPort));
        } else {
            System.out.println("Usage: WordCountTrident 0(storm-local)|1(storm-cluster) redis-host redis-port");
        }
    }

}
