package com.modelcollab.config;

import com.modelcollab.collaboration.interceptor.StompChannelInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configuracion del broker STOMP sobre WebSocket para la colaboracion en tiempo
 * real (seccion 6 del documento de arquitectura).
 *
 * <p>Nota de diseno sobre los prefijos: el documento pide un broker simple con
 * prefijos {@code /topic} y {@code /user}. En Spring, {@code /user} no es un
 * prefijo que se registre directamente en {@link MessageBrokerRegistry#enableSimpleBroker},
 * sino el {@code userDestinationPrefix}: cuando un handler envia a
 * {@code /user/queue/locks}, {@code UserDestinationMessageHandler} lo reescribe
 * internamente a una cola fisica {@code /queue/user{sessionId}-locks} y esa cola
 * fisica es la que debe estar habilitada en el broker simple (prefijo
 * {@code /queue}). Por eso aqui se habilita {@code /topic} y {@code /queue} en el
 * broker, y se fija {@code /user} como {@code userDestinationPrefix}: el efecto
 * observable para los clientes (suscribirse a {@code /user/queue/locks}) es
 * exactamente el pedido.</p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompChannelInterceptor stompChannelInterceptor;

    public WebSocketConfig(StompChannelInterceptor stompChannelInterceptor) {
        this.stompChannelInterceptor = stompChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-stomp")
                .setAllowedOriginPatterns("*")
                .withSockJS();

        // Endpoint adicional sin SockJS para clientes que hablan STOMP-sobre-WebSocket
        // nativo (p.ej. librerias STOMP de escritorio/movil que no necesitan fallback HTTP).
        registry.addEndpoint("/ws-stomp")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompChannelInterceptor);
    }
}
