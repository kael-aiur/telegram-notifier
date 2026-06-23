package site.kael.telegram.notifier;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import site.kael.telegram.notifier.core.dao.NotificationRuleDao;
import site.kael.telegram.notifier.core.dao.PushChannelDao;
import site.kael.telegram.notifier.core.model.PushChannel;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PushChannelServiceTest {

    /**
     * 复现并锁定生产事故:当 Bark 服务器建立 TCP 连接却不返回响应(死连接 / 网络抖动),
     * {@code send()} 必须在 read timeout 内返回失败,而不是永久阻塞。后一种情况会把
     * 单线程的未读扫描调度器 {@code scheduling-1} 冻结(线程栈卡在 HttpClient.send ->
     * CompletableFuture.get),导致整个监控循环停摆。
     *
     * <p>用 readTimeout=2s、服务器 stall 5s 来验证:send 应在 ~2s 超时返回,而非等 5s。
     */
    @Test
    @Timeout(30)
    void sendReturnsFailureWithinTimeoutWhenServerStalls() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(5_000); // 接受连接,5s 内不返回响应体(readTimeout 2s 应先触发)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            var baseUri = "http://127.0.0.1:" + server.getAddress().getPort();
            var channel = new PushChannel(7L, "bark", "BARK", true,
                    Map.of("serverUrl", baseUri, "deviceKey", "k"),
                    Instant.now(), Instant.now());

            // readTimeout=2s:send 应在 ~2s 超时,而不是等服务器 15s
            var service = new PushChannelService(
                    mock(PushChannelDao.class), mock(NotificationRuleDao.class),
                    Duration.ofSeconds(2), Duration.ofSeconds(2));

            var start = System.nanoTime();
            var result = service.send(channel, "hello");
            var elapsed = Duration.ofNanos(System.nanoTime() - start);
            System.out.println(">>> PushChannelServiceTest send elapsed=" + elapsed
                    + " success=" + result.success() + " message=" + result.message());

            assertThat(result.success())
                    .as("stalled server must surface as a failed delivery, not a hang")
                    .isFalse();
            assertThat(elapsed.toSeconds())
                    .as("should return near read timeout (2s), not wait for server stall; elapsed=" + elapsed)
                    .isLessThan(10L);
        } finally {
            server.stop(0);
        }
    }
}
