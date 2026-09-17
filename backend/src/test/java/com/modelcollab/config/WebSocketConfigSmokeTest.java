package com.modelcollab.config;

import com.modelcollab.auth.service.JwtService;
import com.modelcollab.collaboration.interceptor.StompChannelInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic-only test (not part of the required deliverables): loads just
 * {@link WebSocketConfig} + {@link StompChannelInterceptor} with a real
 * embedded servlet container, but with datasource/JPA/security
 * auto-configuration excluded, to check whether registering the "/ws-stomp"
 * STOMP endpoint twice (once with SockJS, once without) causes a mapping
 * conflict, and whether BOTH registrations are actually reachable (as
 * opposed to just "no exception at startup").
 */
@SpringBootTest(
        classes = {WebSocketConfig.class, StompChannelInterceptor.class, JwtService.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class
})
class WebSocketConfigSmokeTest {

    @LocalServerPort
    private int port;

    @Test
    void contextLoadsWithBothWsStompRegistrations() {
        // If registering "/ws-stomp" twice (SockJS + raw) conflicted, the
        // ApplicationContext would fail to refresh and this test would fail
        // before reaching this point.
    }

    @Test
    void sockJsInfoEndpointIsReachable() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ws-stomp/info"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        // SockJS info payload contains "websocket":true when the transport is enabled.
        assertThat(response.body()).contains("\"websocket\"");
    }

    @Test
    void rawWebSocketHandshakeSucceeds() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        CountDownLatch connected = new CountDownLatch(1);
        WebSocketSession session = client.execute(
                new TextWebSocketHandler() {
                    @Override
                    public void afterConnectionEstablished(WebSocketSession session) {
                        connected.countDown();
                    }
                },
                new WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/ws-stomp")
        ).get(5, TimeUnit.SECONDS);

        try {
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(session.isOpen()).isTrue();
        } finally {
            session.close();
        }
    }
}
