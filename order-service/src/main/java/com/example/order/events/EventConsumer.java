package com.example.order.events;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class EventConsumer {

    // Versión reactiva del método handleEvent
    public Mono<Void> handleEvent(EventWrapper<?> event) {
        return Mono.fromRunnable(() -> {
            System.out.println("Event received:");
            System.out.println("Type: " + event.type());
            System.out.println("Payload: " + event.payload());
            // Aquí puedes enrutar a handlers específicos por tipo
        });
    }
}