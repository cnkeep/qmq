/*
 * Copyright 2018 Qunar, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package qunar.tc.qmq.store;

import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Created by zhaohui.yu
 * 2020/6/6
 */
public class PullLogMemTable extends MemTable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PullLogMemTable.class);

    public static final int SEQUENCE_SIZE = 10 * 1024 * 1024;

    private static final long MAX_FLUSH_TIME = TimeUnit.MINUTES.toNanos(10);

    private final ConcurrentMap<String, PullLogSequence> messageSequences = new ConcurrentHashMap<>();

    public static final int ENTRY_SIZE = Integer.BYTES;

    private volatile int writerIndex;

    private final long createTs;
    private Snapshot<ActionCheckpoint> snapshot;

    public PullLogMemTable(final long tabletId, final long beginOffset, final int capacity) {
        super(tabletId, beginOffset, capacity);
        this.createTs = System.nanoTime();
    }

    //为了避免长时间不做checkpoint，强制10分钟刷一次
    @Override
    public boolean checkWritable(final int writeBytes) {
        return (getCapacity() - writerIndex > writeBytes) && (System.nanoTime() - createTs < MAX_FLUSH_TIME);
    }

    public void pull(String subject, String group, String consumerId, long firstPullSequence, int count,
                     long firstMessageSequence, long lastMessageSequence) {
        PullLogSequence pullLogSequence = messageSequences.computeIfAbsent(keyOf(subject, group, consumerId), k -> new PullLogSequence());
        pullLogSequence.pull(firstPullSequence, firstMessageSequence, lastMessageSequence);
        writerIndex += (count * ENTRY_SIZE);
    }

    public void ack(String subject, String group, String consumerId, long firstSequence, long lastSequence) {
        String consumer = keyOf(subject, group, consumerId);
        PullLogSequence pullLogSequence = messageSequences.get(consumer);
        if (pullLogSequence == null) return;
        //将已经ack的pull log截断
        int ackSize = pullLogSequence.ack(firstSequence, lastSequence);
        writerIndex -= ackSize * ENTRY_SIZE;

        //如果全部ack掉了，则将该consumer删除掉
        if (pullLogSequence.messagesInRange.isEmpty()) {
            messageSequences.remove(consumer);
        }
    }

    public long getConsumerLogSequence(String subject, String group, String consumerId, long pullSequence) {
        String key = keyOf(subject, group, consumerId);
        PullLogSequence pullLogSequence = messageSequences.get(key);
        if (pullLogSequence == null) return -1L;

        return pullLogSequence.getMessageSequence(pullSequence);
    }

    public static String keyOf(String subject, String group, String consumerId) {
        return consumerId + "@" + group + "@" + subject;
    }

    public void dump(ByteBuf buffer, Map<String, PullLogIndexEntry> indexMap) {
        for (Map.Entry<String, PullLogSequence> entry : messageSequences.entrySet()) {
            LinkedList<Range> messagesInRange = entry.getValue().messagesInRange;
            Range first = messagesInRange.peekFirst();
            if (first == null) continue;

            final long baseMessageSequence = first.start;
            PullLogIndexEntry indexEntry = new PullLogIndexEntry(entry.getValue().basePullSequence, baseMessageSequence, buffer.writerIndex(), size(messagesInRange));
            indexMap.put(entry.getKey(), indexEntry);
            for (Range range : messagesInRange) {
                for (long i = range.start; i <= range.end; ++i) {
                    buffer.writeInt((int) (i - baseMessageSequence));
                }
            }
        }
    }

    private int size(LinkedList<Range> messagesInRange) {
        int sum = 0;
        for (Range range : messagesInRange) {
            sum += range.size();
        }
        return sum;
    }

    @Override
    public void close() {

    }

    @Override
    public int getTotalDataSize() {
        return writerIndex;
    }

    public void setCheckpointSnapshot(Snapshot<ActionCheckpoint> snapshot) {
        this.snapshot = snapshot;
    }

    public Snapshot<ActionCheckpoint> getCheckpointSnapshot() {
        return snapshot;
    }

    private static class PullLogSequence {

        private long basePullSequence = -1L;

        private final LinkedList<Range> messagesInRange = new LinkedList<>();

        public void pull(long pullSequence, long startOfMessageSequence, long endOfMessageSequence) {
            if (basePullSequence == -1L) {
                basePullSequence = pullSequence;
            }

            if (!merge(startOfMessageSequence, endOfMessageSequence)) {
                messagesInRange.add(Range.create(startOfMessageSequence, endOfMessageSequence));
            }
        }

        private boolean merge(final long start, final long end) {
            final Range last = messagesInRange.peekLast();
            if (last != null) {
                final long lastEnd = last.end;
                if (lastEnd + 1 == start) {
                    last.end = end;
                    return true;
                }
            }
            return false;
        }

        public int ack(final long firstSequence, final long lastSequence) {
            //ack的范围已经被挤出了内存
            if (lastSequence < basePullSequence) {
                return 0;
            }

            //这也是异常情况，ack不连续，ack了中间的一个区间，应该是什么地方出问题了
            if (firstSequence > basePullSequence) {
                return 0;
            }

            //eg. lastSequence = 1234, basePullSequence = 1234, then nextAckSequence = 1235, ackSize = 1
            long ackSize = lastSequence - basePullSequence + 1;
            final int result = (int) ackSize;
            Iterator<Range> iterator = messagesInRange.iterator();
            while (iterator.hasNext() && ackSize > 0) {
                final Range range = iterator.next();

                //1234, 1235, 1236
                // [5, 7] => 5, 6, 7

                //ackSize = 1
                //range.size() => 3
                //ackSize <= range.size(), range.start = range.start + ackSize = 5 + 1 = 6
                if (ackSize < range.size()) {
                    range.start += ackSize;
                    break;
                }

                //if lastSequence = 1237, basePullSequence = 1234, then nextAckSequence = 1238, ackSize = 4
                //1234, 1235, 1236
                //first size: [5, 7] => 5, 6, 7, first range.size() => 3

                //1237, 1238, 1239, 1240
                //second size: [12, 15] => 12, 13, 14, 15, second range.size() => 4
                //then remove first size, second size range.start = second range.start + 1 = 12 + 1 = 13
                if (ackSize >= range.size()) {
                    iterator.remove();
                    ackSize -= range.size();
                }
            }
            basePullSequence = lastSequence + 1;
            return result;
        }

        public long getMessageSequence(long pullSequence) {
            long offset = pullSequence - basePullSequence;
            if (offset < 0) return -1L;

            for (Range range : messagesInRange) {
                if (offset < range.size()) {
                    return range.start + offset;
                }
                offset -= range.size();
            }
            return -1L;
        }
    }

    private static class Range {
        private long start;

        private long end;

        public static Range create(long start, long end) {
            Range range = new Range();
            range.start = start;
            range.end = end;
            return range;
        }

        long size() {
            return end - start + 1;
        }
    }
}
