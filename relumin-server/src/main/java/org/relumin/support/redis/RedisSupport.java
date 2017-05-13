package org.relumin.support.redis;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.relumin.model.ClusterNode;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.lambdaworks.redis.RedisClient;
import com.lambdaworks.redis.RedisURI;
import com.lambdaworks.redis.api.StatefulRedisConnection;
import com.lambdaworks.redis.api.sync.RedisCommands;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class RedisSupport {
    public static final String REDIS_SCHEME = "redis://";
    public static final int ALL_SLOTS_SIZE = 16384;

    public Map<String, String> parseInfoResult(String result) {
        Map<String, String> map = colonSeparatedTextToMap(result);
        map.put("_timestamp", String.valueOf(System.currentTimeMillis()));
        return map;
    }

    public Map<String, String> parseClusterInfoResult(String result) {
        return colonSeparatedTextToMap(result);
    }

    public List<ClusterNode> parseClusterNodesResult(String result, String hostAndPort) {
        final List<ClusterNode> clusterNodes = Lists.newArrayList();
        final String host = Splitter.on(":").trimResults().splitToList(hostAndPort).get(0);

        for (final String resultLine : StringUtils.split(result, "\n")) {
            final ClusterNode clusterNode = new ClusterNode();

            final String[] resultLineArray = StringUtils.split(resultLine);
            clusterNode.setNodeId(resultLineArray[0]);

            final String eachHostAndPort = resultLineArray[1];
            if (StringUtils.isBlank(hostAndPort)) {
                clusterNode.setHostAndPort(eachHostAndPort);
            } else {
                if (StringUtils.startsWith(eachHostAndPort, ":")) {
                    clusterNode.setHostAndPort(hostAndPort);
                } else {
                    final List<String> eachHostAndPortList = Splitter.on(":").trimResults()
                                                                     .splitToList(eachHostAndPort);
                    final String eachHost = eachHostAndPortList.get(0);
                    final String eachPort = eachHostAndPortList.get(1);
                    if ("127.0.0.1".equals(eachHost) || "localhost".equals(eachHost)) {
                        clusterNode.setHostAndPort(host + ':' + eachPort);
                    } else {
                        clusterNode.setHostAndPort(eachHostAndPort);
                    }
                }
            }

            final String eachFlag = resultLineArray[2];
            final List<String> eachFlagList = Splitter.on(",").trimResults()
                                                      .omitEmptyStrings().splitToList(eachFlag);
            clusterNode.setFlags(Sets.newLinkedHashSet(eachFlagList));

            clusterNode.setMasterNodeId("-".equals(resultLineArray[3]) ? "" : resultLineArray[3]);

            clusterNode.setPingSent(Long.valueOf(resultLineArray[4]));
            clusterNode.setPongReceived(Long.valueOf(resultLineArray[5]));

            clusterNode.setConfigEpoch(Long.valueOf(resultLineArray[6]));

            clusterNode.setConnected("connected".equals(resultLineArray[7]));

            final List<String> slots = Lists.newArrayList();
            for (int i = 8; i < resultLineArray.length; i++) {
                if (clusterNode.hasFlag("myself") && StringUtils.startsWith(resultLineArray[i], "[")) {
                    final String trimmed = StringUtils.substring(resultLineArray[i], 1, -1);
                    if (StringUtils.indexOf(trimmed, "->-") != StringUtils.INDEX_NOT_FOUND) {
                        final String[] trimmedArray = StringUtils.split(trimmed, "->-");
                        clusterNode.getMigrating().put(Integer.valueOf(trimmedArray[0]), trimmedArray[1]);
                    } else if (StringUtils.indexOf(trimmed, "-<-") != StringUtils.INDEX_NOT_FOUND) {
                        final String[] trimmedArray = StringUtils.split(trimmed, "-<-");
                        clusterNode.getImporting().put(Integer.valueOf(trimmedArray[0]), trimmedArray[1]);
                    }
                } else {
                    slots.add(resultLineArray[i]);
                }
            }

            slots.forEach(v -> {
                if (StringUtils.indexOf(v, "-") == StringUtils.INDEX_NOT_FOUND) {
                    clusterNode.getServedSlotsSet().add(Integer.valueOf(v));
                } else {
                    final String[] startAndEnd = StringUtils.split(v, "-");
                    int start = Integer.valueOf(startAndEnd[0]);
                    int end = Integer.valueOf(startAndEnd[1]);
                    for (int i = start; i <= end; i++) {
                        clusterNode.getServedSlotsSet().add(i);
                    }
                }
            });

            clusterNodes.add(clusterNode);
        }

        return clusterNodes;
    }

    public String slotsDisplay(Collection<Integer> slots) {
        if (CollectionUtils.isEmpty(slots)) {
            return "";
        }

        final List<String> result = Lists.newArrayList();

        int i = 0;
        int first = 0;
        int last = 0;
        for (final int current : Sets.newTreeSet(slots)) {
            // if first loop
            if (i == 0) {
                if (slots.size() == 1) {
                    result.add(String.valueOf(current));
                    break;
                }

                first = current;
                last = current;
                i++;
                continue;
            }

            if (current == last + 1) {
                // if last loop
                if (i == slots.size() - 1) {
                    result.add(new StringBuilder().append(first).append('-').append(current).toString());
                    break;
                }

                last = current;
                i++;
                continue;
            } else {
                // if last loop
                if (i == slots.size() - 1) {
                    if (first == last) {
                        result.add(String.valueOf(first));
                    } else {
                        result.add(new StringBuilder().append(first).append('-').append(last).toString());
                    }
                    result.add(String.valueOf(current));
                    break;
                }

                if (first == last) {
                    result.add(String.valueOf(first));
                } else {
                    result.add(new StringBuilder().append(first).append('-').append(last).toString());
                }
                first = current;
                last = current;
                i++;
                continue;
            }
        }

        return StringUtils.join(result, ",");
    }

    /**
     *
     * @param slotsStr
     * @return
     * @throws NumberFormatException
     */
    public Set<Integer> getSlots(List<String> slotsStr) {
        final Set<Integer> slots = Sets.newTreeSet();

        slotsStr.forEach(v -> {
            v = StringUtils.trim(v);

            final String[] slotArray = StringUtils.split(v, "-");

            final int start;
            try {
                start = Integer.valueOf(slotArray[0]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(String.format("'%s' is invalid format.", v), e);
            }

            final int end;
            if (slotArray.length > 1) {
                try {
                    end = Integer.valueOf(slotArray[1]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(String.format("'%s' is invalid format.", v), e);
                }
            } else {
                end = start;
            }

            if (start > end) {
                throw new IllegalArgumentException(
                        String.format("'%s' is invalid format. start slot must be equal or less than end slot.",
                                      v));
            }
            for (int i = start; i <= end; i++) {
                slots.add(i);
            }
        });

        return slots;
    }

    private static Map<String, String> colonSeparatedTextToMap(String result) {
        final Map<String, String> map = Maps.newLinkedHashMap();

        final String[] line = StringUtils.split(result, "\n");
        for (final String each : line) {
            final String[] eachArray = StringUtils.split(each, ":");
            if (eachArray.length != 2) {
                continue;
            }

            final String key = StringUtils.trim(eachArray[0]);
            final String value = StringUtils.trim(eachArray[1]);
            map.put(key, value);
        }

        return map;
    }

    public void executeCommands(final RedisURI redisUri, final RedisCommandsExecutable func) {
        RedisClient redisClient = null;
        StatefulRedisConnection<String, String> connection = null;
        try {
            redisClient = RedisClient.create(redisUri);
            connection = redisClient.connect();
            final RedisCommands<String, String> commands = connection.sync();
            func.execute(commands);
        } finally {
            if (connection != null) {
                connection.close();
            }
            if (redisClient != null) {
                redisClient.shutdown(0, 0, TimeUnit.MILLISECONDS);
            }
        }
    }

    public <T> T computeCommands(final RedisURI redisUri, final RedisCommandsComputable<T> func) {
        RedisClient redisClient = null;
        StatefulRedisConnection<String, String> connection = null;
        try {
            redisClient = RedisClient.create(redisUri);
            connection = redisClient.connect();
            final RedisCommands<String, String> commands = connection.sync();
            return func.compute(commands);
        } finally {
            if (connection != null) {
                connection.close();
            }
            if (redisClient != null) {
                redisClient.shutdown(0, 0, TimeUnit.MILLISECONDS);
            }
        }
    }

    @FunctionalInterface
    public interface RedisCommandsExecutable {
        void execute(RedisCommands<String, String> commands);
    }

    @FunctionalInterface
    public interface RedisCommandsComputable<T> {
        T compute(RedisCommands<String, String> commands);
    }
}
