package com.example.appender;

import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.chavaillaz.appender.TransmissionResult;
import com.chavaillaz.appender.log4j.AbstractBatchLogDelivery;

/**
 * Sends each batch to a collector listening on a TCP port, over a connection of its own, a line per document.
 */
public class ExampleLogDelivery extends AbstractBatchLogDelivery<ExampleConfiguration> {

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    public ExampleLogDelivery(ExampleConfiguration configuration) {
        super(configuration);
    }

    @Override
    protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
        ExampleConfiguration configuration = getConfiguration();
        // Called on a virtual thread of its own, which a blocking client may hold for as long as the request takes
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(configuration.collectorHost(), configuration.collectorPort()),
                    CONNECT_TIMEOUT_MILLIS);
            Writer writer = new OutputStreamWriter(socket.getOutputStream(), UTF_8);
            for (Map<String, Object> document : documents) {
                writer.write(toLine(document));
            }
            writer.flush();
            return completedFuture(TransmissionResult.success());
        } catch (IOException e) {
            // Logged with its cause by the library, which retries the documents
            return completedFuture(TransmissionResult.failure(e));
        }
    }

    /**
     * Writes a document as a line of {@code field=value} pairs, each encoded as in a URL so that none holds a
     * line break. A real delivery writes what its collector expects instead, JSON through the library of its
     * client for instance.
     *
     * @param document The document to write
     * @return The line, ended
     */
    private static String toLine(Map<String, Object> document) {
        return document.entrySet().stream()
                .map(field -> encode(field.getKey(), UTF_8) + "=" + encode(String.valueOf(field.getValue()), UTF_8))
                .collect(joining("&", "", "\n"));
    }

}
