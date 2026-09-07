package com.example.demo.collaboration.interceptor;

import org.springframework.messaging.MessagingException;

/**
 * Excepcion controlada lanzada por {@link StompChannelInterceptor} cuando el
 * frame STOMP CONNECT no incluye un JWT valido (header {@code Authorization}
 * ausente, malformado, invalido o expirado). Al propagarse desde
 * {@code preSend}, el framework la traduce en un frame STOMP ERROR devuelto al
 * cliente y la sesion no llega a establecerse.
 */
public class StompAuthenticationException extends MessagingException {
    public StompAuthenticationException(String message) {
        super(message);
    }
}
