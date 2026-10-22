package dev.toka.protect.log;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class LogWriterTest {
    private static LogRecord record(int i) {
        return LogRecord.byPlayer(i, UUID.randomUUID(), "p", Action.PLACE, "w", i, 64, 0, 1, 0);
    }

    private static final class Collecting implements LogWriter.Sink<LogRecord> {
        final List<LogRecord> all = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger maintenance = new AtomicInteger();
        volatile boolean fail;
        public void write(List<LogRecord> batch) throws Exception {
            if (fail) throw new IllegalStateException("disk gone");
            all.addAll(batch);
            batchSizes.add(batch.size());
        }
        public void maintain() { maintenance.incrementAndGet(); }
    }

    @Test public void everythingQueuedIsWrittenByCloseAndInBatches() {
        var sink = new Collecting();
        var writer = new LogWriter<LogRecord>("t", sink, 1000, 50, 20, 60_000, error -> fail(error.toString()));
        for (int i = 0; i < 200; i++) assertTrue(writer.offer(record(i)));
        writer.close();
        assertEquals(200, sink.all.size());
        assertEquals(200, writer.written());
        assertTrue(sink.batchSizes.stream().allMatch(size -> size <= 50));
        assertEquals(0, writer.dropped());
    }

    @Test public void aFullQueueDropsRecordsInsteadOfWaiting() throws Exception {
        var stalled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sink = new LogWriter.Sink<LogRecord>() {
            public void write(List<LogRecord> batch) throws Exception { stalled.countDown(); release.await(5, TimeUnit.SECONDS); }
            public void maintain() { }
        };
        var writer = new LogWriter<LogRecord>("t", sink, 5, 1, 5, 60_000, error -> { });
        writer.offer(record(0));
        assertTrue(stalled.await(5, TimeUnit.SECONDS));
        int accepted = 0;
        for (int i = 1; i <= 20; i++) if (writer.offer(record(i))) accepted++;
        assertEquals(5, accepted);
        assertEquals(15, writer.dropped());
        release.countDown();
        writer.close();
    }

    @Test public void aFailedWriteIsReportedAndLaterBatchesStillGo() throws Exception {
        var sink = new Collecting();
        var errors = new AtomicInteger();
        var writer = new LogWriter<LogRecord>("t", sink, 100, 10, 10, 60_000, error -> errors.incrementAndGet());
        sink.fail = true;
        writer.offer(record(1));
        long deadline = System.currentTimeMillis() + 3000;
        while (errors.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(1, errors.get());
        sink.fail = false;
        writer.offer(record(2));
        writer.close();
        assertEquals(1, sink.all.size());
        assertEquals(1, writer.failed());
    }

    @Test public void housekeepingRunsBetweenBatches() throws Exception {
        var sink = new Collecting();
        var writer = new LogWriter<LogRecord>("t", sink, 100, 10, 10, 30, error -> { });
        long deadline = System.currentTimeMillis() + 3000;
        while (sink.maintenance.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        writer.close();
        assertTrue(sink.maintenance.get() > 0);
    }
}
