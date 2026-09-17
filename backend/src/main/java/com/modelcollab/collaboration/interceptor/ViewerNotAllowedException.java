package com.modelcollab.collaboration.interceptor;

import org.springframework.messaging.MessagingException;

/**
 * Excepcion controlada lanzada por {@link StompChannelInterceptor} cuando un
 * usuario con rol VIEWER intenta enviar una mutacion. Al propagarse desde
 * {@code preSend} en el canal de entrada de Spring STOMP, el framework la
 * traduce automaticamente en un frame STOMP ERROR devuelto solo al cliente
 * emisor (el mensaje nunca se reenvia al controlador ni a la sala).
 */
public class ViewerNotAllowedException extends MessagingException {
    public ViewerNotAllowedException(String message) {
        super(message);
    }
}
