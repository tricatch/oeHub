package tricatch.oe.proxy.event;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HttpEventManagerTest {

    @Test
    void oneFailingChannel_doesNotDeregisterOtherChannelsForTheSameClient() throws Exception {
        HttpEventManager manager = HttpEventManager.getInstance();
        String clientId = "test-client-" + UUID.randomUUID();

        List<HttpEvent> receivedByGood = new CopyOnWriteArrayList<>();
        AtomicInteger badCallCount = new AtomicInteger(0);

        HttpEventConsumer bad = new HttpEventConsumer() {
            public String getClientId() { return clientId; }
            public String getChannelId() { return "bad"; }
            public void process(HttpEvent event) throws IOException {
                badCallCount.incrementAndGet();
                throw new IOException("simulated channel failure");
            }
        };
        HttpEventConsumer good = new HttpEventConsumer() {
            public String getClientId() { return clientId; }
            public String getChannelId() { return "good"; }
            public void process(HttpEvent event) {
                receivedByGood.add(event);
            }
        };

        manager.addEventConsumer(bad);
        manager.addEventConsumer(good);

        try {
            for (int i = 0; i < 3; i++) {
                manager.enqueue(new HttpEvent(clientId, "rid" + i, HttpEventType.REQ_HEADER));
            }

            waitUntil(() -> receivedByGood.size() >= 3, 5000);

            // "good" must receive every event even though "bad" (registered under the same
            // clientId) throws on every one of its own — a failing channel must not take down
            // its sibling channels.
            assertThat(receivedByGood).hasSize(3);
            assertThat(badCallCount.get()).isGreaterThanOrEqualTo(1);
        } finally {
            manager.removeEventConsumer(bad);
            manager.removeEventConsumer(good);
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
