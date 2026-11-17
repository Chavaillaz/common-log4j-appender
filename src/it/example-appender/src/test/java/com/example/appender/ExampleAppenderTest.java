package com.example.appender;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the appender the way an application does: Log4j2 finds its configuration on the class path, creates the
 * plugin it names from the descriptor the annotation processor wrote, and stops it when shut down.
 */
@DisplayName("Example appender")
class ExampleAppenderTest {

    private static final int EVENTS = 25;

    @Test
    @DisplayName("Delivers every event an application logs to the collector its configuration names")
    void testDeliversEveryLoggedEvent() throws Exception {
        // given: a collector listening on a free port, which the configuration reads from a system property
        try (ServerSocket collector = new ServerSocket(0, 50, InetAddress.getByName("localhost"))) {
            BlockingQueue<String> received = new LinkedBlockingQueue<>();
            Thread.ofVirtual().start(() -> receive(collector, received));
            System.setProperty("collector.port", String.valueOf(collector.getLocalPort()));

            // when: the application logs, then shuts Log4j2 down, which sends what is still pending
            Logger logger = LogManager.getLogger("com.example.application.Orders");
            for (int order = 1; order <= EVENTS; order++) {
                logger.info("Order {} shipped", order);
            }
            LogManager.shutdown();

            // then: every event reached the collector, once, as a document telling where it comes from
            List<Map<String, String>> documents = new ArrayList<>();
            for (int i = 1; i <= EVENTS; i++) {
                String line = received.poll(30, SECONDS);
                assertThat(line).as("document %d of %d", i, EVENTS).isNotNull();
                documents.add(decode(line));
            }
            assertThat(documents)
                    .extracting(document -> document.get("logmessage"))
                    .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, EVENTS)
                            .mapToObj(order -> "Order " + order + " shipped")
                            .toList());
            assertThat(documents).allSatisfy(document -> assertThat(document)
                    .containsEntry("application", "example-application")
                    .containsEntry("environment", "test")
                    .containsEntry("level", "INFO")
                    .containsEntry("logger", "com.example.application.Orders")
                    .containsKeys("datetime", "host", "thread"));
        }
    }

    /**
     * Reads the lines of every connection the collector accepts, until it is closed.
     *
     * @param collector The listening socket
     * @param received  Where the lines go
     */
    private static void receive(ServerSocket collector, BlockingQueue<String> received) {
        while (true) {
            try (Socket connection = collector.accept();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), UTF_8))) {
                reader.lines().forEach(received::add);
            } catch (IOException e) {
                // Closed, the test being over
                return;
            }
        }
    }

    /**
     * Reads a line back into the fields of its document (see {@code ExampleLogDelivery}).
     *
     * @param line The line received
     * @return The fields, by name
     */
    private static Map<String, String> decode(String line) {
        return Arrays.stream(line.split("&"))
                .map(field -> field.split("=", 2))
                .collect(toMap(field -> URLDecoder.decode(field[0], UTF_8), field -> URLDecoder.decode(field[1], UTF_8)));
    }

}
