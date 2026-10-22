package dev.toka.protect.log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Takes records from the server thread without ever waiting and writes them in batches on its own thread. When the queue is
 * full the record is dropped and counted: losing a log line is better than slowing the server. A failed write drops that
 * batch and is reported, so a broken disk cannot make the queue grow without bound.
 */
public final class LogWriter implements AutoCloseable {
    /** Where batches go; the database in the server, a fake in tests. */
    public interface Sink {
        void write(List<LogRecord> batch) throws Exception;
        /** Housekeeping, called now and then between batches. */
        void maintain() throws Exception;
    }

    private final BlockingQueue<LogRecord> queue;
    private final Sink sink;
    private final long flushMillis;
    private final long maintainMillis;
    private final int batchSize;
    private final Consumer<Throwable> onError;
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile boolean running = true;
    private final Thread thread;

    public LogWriter(Sink sink, int queueSize, int batchSize, long flushMillis, long maintainMillis, Consumer<Throwable> onError) {
        if (queueSize < 1 || batchSize < 1 || flushMillis < 1 || maintainMillis < 1) throw new IllegalArgumentException("sizes must be positive");
        this.sink = sink;
        this.queue = new ArrayBlockingQueue<>(queueSize);
        this.batchSize = batchSize;
        this.flushMillis = flushMillis;
        this.maintainMillis = maintainMillis;
        this.onError = onError;
        this.thread = new Thread(this::run, "tokaProtect-log");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Never blocks. Returns false when the record was dropped. */
    public boolean offer(LogRecord record) {
        if (queue.offer(record)) return true;
        dropped.incrementAndGet();
        return false;
    }

    public long written() { return written.get(); }
    public long dropped() { return dropped.get(); }
    public long failed() { return failed.get(); }
    public int queued() { return queue.size(); }

    private void run() {
        long lastMaintenance = System.currentTimeMillis();
        List<LogRecord> batch = new ArrayList<>(batchSize);
        while (running || !queue.isEmpty()) {
            try {
                LogRecord first = queue.poll(flushMillis, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, batchSize - 1);
                    flush(batch);
                }
                long now = System.currentTimeMillis();
                if (running && now - lastMaintenance >= maintainMillis) {
                    lastMaintenance = now;
                    try { sink.maintain(); } catch (Exception error) { onError.accept(error); }
                }
            } catch (InterruptedException stop) {
                running = false;
            }
        }
    }

    private void flush(List<LogRecord> batch) {
        try {
            sink.write(List.copyOf(batch));
            written.addAndGet(batch.size());
        } catch (Exception error) {
            failed.addAndGet(batch.size());
            onError.accept(error);
        } finally {
            batch.clear();
        }
    }

    /** Stops accepting nothing new to wait for, writes what is queued, and ends the thread. */
    @Override public void close() {
        running = false;
        thread.interrupt();
        try { thread.join(10_000); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
    }
}
