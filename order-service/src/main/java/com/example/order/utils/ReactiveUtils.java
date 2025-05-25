package com.example.order.utils;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Utilidades para operaciones reactivas comunes
 */
public class ReactiveUtils {

    /**
     * Enriquece un Mono con contexto de diagnóstico MDC
     * @param context Contexto a añadir al MDC
     * @param operation Operación reactiva a ejecutar con el contexto
     * @return Mono con el contexto MDC aplicado
     */
    public static <T> Mono<T> withDiagnosticContext(Map<String, String> context, Supplier<Mono<T>> operation) {
        return Mono.fromCallable(() -> {
                    context.forEach(MDC::put);
                    return true;
                })
                .flatMap(result -> operation.get())
                .doFinally(signal -> MDC.clear());
    }

    /**
     * Enriquece un Mono con métricas de tiempo de ejecución
     * @param mono Mono a medir
     * @param meterRegistry Registro de métricas
     * @param metricName Nombre de la métrica
     * @param tags Tags adicionales
     * @return Mono con métricas aplicadas
     */
    public static <T> Mono<T> withMetrics(Mono<T> mono, MeterRegistry meterRegistry, String metricName, Tag... tags) {
        Timer.Sample timer = Timer.start(meterRegistry);

        return mono
                .doOnSuccess(value -> {
                    List<Tag> successTags = new ArrayList<>(Arrays.asList(tags));
                    successTags.add(Tag.of("status", "success"));
                    timer.stop(meterRegistry.timer(metricName, successTags));
                })
                .doOnError(error -> {
                    List<Tag> failedTags = new ArrayList<>(Arrays.asList(tags));
                    failedTags.add(Tag.of("status", "failed"));
                    failedTags.add(Tag.of("error_type", error.getClass().getSimpleName()));
                    timer.stop(meterRegistry.timer(metricName, failedTags));
                });
    }

    /**
     * Combina la aplicación de contexto MDC y métricas con array de tags
     */
    public static <T> Mono<T> withContextAndMetrics(
            Map<String, String> context,
            Supplier<Mono<T>> operation,
            MeterRegistry meterRegistry,
            String metricName,
            Tag... tags) {

        return withDiagnosticContext(context, () ->
                withMetrics(operation.get(), meterRegistry, metricName, tags));
    }

    /**
     * ✅ SOBRECARGA FALTANTE: Combina la aplicación de contexto MDC y métricas con un solo tag
     */
    public static <T> Mono<T> withContextAndMetrics(
            Map<String, String> context,
            Supplier<Mono<T>> operation,
            MeterRegistry meterRegistry,
            String metricName,
            Tag tag) {

        return withContextAndMetrics(context, operation, meterRegistry, metricName, new Tag[]{tag});
    }

    /**
     * ✅ SOBRECARGA ADICIONAL: Para compatibilidad con Iterable<Tag>
     */
    public static <T> Mono<T> withContextAndMetrics(
            Map<String, String> context,
            Supplier<Mono<T>> operation,
            MeterRegistry meterRegistry,
            String metricName,
            Iterable<Tag> tags) {

        // Convertir Iterable<Tag> a Tag[]
        List<Tag> tagList = new ArrayList<>();
        if (tags != null) {
            tags.forEach(tagList::add);
        }
        Tag[] tagArray = tagList.toArray(new Tag[0]);

        return withContextAndMetrics(context, operation, meterRegistry, metricName, tagArray);
    }

    /**
     * Crea un mapa de contexto para diagnóstico
     */
    public static Map<String, String> createContext(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Key-value pairs must be even");
        }

        Map<String, String> context = new HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            context.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return context;
    }
}