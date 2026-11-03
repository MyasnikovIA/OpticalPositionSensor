package ru.miacomsoft.optical_position_sensor;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SensorBroadcaster {

    public interface Subscriber {
        void onReading(JSONObject r) throws Exception;
    }

    private final Map<String, Subscriber> subs = new ConcurrentHashMap<String, Subscriber>();
    private final AtomicLong idGen = new AtomicLong();
    private volatile JSONObject lastReading;
    private volatile long lastPublishMs;
    private final AtomicLong publishSeq = new AtomicLong();

    public String subscribe(Subscriber s) {
        String id = "s-" + idGen.incrementAndGet();
        subs.put(id, s);
        JSONObject last = lastReading;
        if (last != null) {
            try { s.onReading(last); } catch (Exception ignored) {}
        }
        return id;
    }

    public void unsubscribe(String id) { if (id != null) subs.remove(id); }
    public int count() { return subs.size(); }
    public JSONObject getLast() { return lastReading; }
    public long getLastPublishMs() { return lastPublishMs; }
    public long getPublishSeq() { return publishSeq.get(); }

    /** Разослать всем подписчикам. */
    public void publish(JSONObject r) {
        this.lastReading = r;
        this.lastPublishMs = System.currentTimeMillis();
        this.publishSeq.incrementAndGet();
        for (Map.Entry<String, Subscriber> e : subs.entrySet()) {
            try { e.getValue().onReading(r); }
            catch (Exception ex) { subs.remove(e.getKey()); }
        }
    }

    /**
     * Обновить lastReading БЕЗ рассылки. Нужен для /api/sensor/last, когда
     * измерение не считается «значимым изменением» и не публикуется.
     * lastPublishMs при этом НЕ меняется.
     */
    public void setLatest(JSONObject r) {
        this.lastReading = r;
    }
}