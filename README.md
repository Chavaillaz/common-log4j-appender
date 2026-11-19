# Common Log4j Appender

![Quality Gate](https://github.com/chavaillaz/common-log4j-appender/actions/workflows/code-quality.yml/badge.svg)
![Dependency Check](https://github.com/chavaillaz/common-log4j-appender/actions/workflows/dependency-check.yml/badge.svg)
[![Maven Central](https://maven-badges.sml.io/sonatype-central/com.chavaillaz/common-log4j-appender/badge.svg?subject=Maven%20Central&color=blue)](https://maven-badges.sml.io/sonatype-central/com.chavaillaz/common-log4j-appender)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

This library provides the building blocks to create Log4j2 appenders that ship log events to an external
system (a search engine, a log management platform, a custom HTTP endpoint, ...), with guarantees that
matter for production logging:

- **The application thread is never blocked.** Every call to `append()` only takes an immutable snapshot
  of the event and hands it off to a background thread; conversion and network I/O never happen on the
  calling thread.
- **Logs are sent by batches.** Instead of opening one connection per log line, events are accumulated
  and shipped together, either once a configurable number of messages is reached or after a configurable
  time interval, whichever comes first.
- **Transmissions overlap instead of queuing up behind each other.** Sending a batch never delays the
  accumulation of the next one: each batch is transmitted on its own virtual thread, with a bounded number
  of transmissions in flight at once.

It is used by the [Elasticsearch Appender](https://github.com/chavaillaz/elasticsearch-log4j-appender)
and the [OpenSearch Appender](https://github.com/chavaillaz/opensearch-log4j-appender), which are good
references to look at alongside this documentation.

## Installation

Requires **Java 21 or later** (virtual threads are used internally to transmit batches), and **Log4j2 2.24.0
or later**: the library brings along the version it is compiled with, 2.26.1, and an application managing that
version itself, as one built on Spring Boot does, can stay on an older one down to 2.24.0. The tests and the
example appender run on both.

The dependency is available in Maven Central:

```xml
<dependency>
    <groupId>com.chavaillaz</groupId>
    <artifactId>common-log4j-appender</artifactId>
    <version>2.0.0</version>
</dependency>
```

This page documents version 2. The 1.x versions have another API, which the
[README of the last of them](https://github.com/chavaillaz/common-log4j-appender/blob/1.1.4/README.md) documents.

You usually don't depend on it directly as an application: instead, use (or create) an appender built on
top of it, such as the two implementations linked above.

On the module path, the library is the module `com.chavaillaz.appender`, which requires the Log4j2 and
JSpecify APIs transitively. The module of an appender requires it, and opens the package of its plugin to
Log4j2, which builds the plugin by reflection:

```java
module com.example.appender {
    requires com.chavaillaz.appender;
    opens com.example.appender to org.apache.logging.log4j.core;
}
```

## How it works

Four types are involved; you extend or implement each of them, the conversion only to customize the documents:

| Concept       | Type to extend/implement       | Responsibility                                                                 |
|---------------|---------------------------------|---------------------------------------------------------------------------------|
| Appender      | `AbstractLogDeliveryAppender`   | Plugs into Log4j2, receives events and hands them off to the background thread |
| Delivery      | `AbstractBatchLogDelivery`      | Accumulates converted documents and sends them by batches                      |
| Conversion    | `LogConverter`                  | Turns a `LogEvent` into a `Map<String, Object>` document to be sent            |
| Configuration | `LogConfiguration`              | Carries the settings shared by the three components above                      |

The flow for a single log event is:

1. Log4j2 calls `append(LogEvent)` on your appender (application thread).
2. The event is snapshotted immediately and submitted to a single background thread; `append()` returns
   right away, never waiting on that task.
3. The background thread converts the event (with a `DefaultLogConverter`, or the `LogConverter` your
   appender returns) and hands it to your `LogDelivery`, which stacks it into the current batch.
4. Once `FlushThreshold` documents accumulate, or the batch fills up to `MaxPendingDocuments` (or
   `MaxPendingBytes`) first, or when the periodic flush runs every `FlushInterval`, the batch is due: if
   bigger than `MaxRequestDocuments` (or heavier than `MaxRequestBytes`), it is first split into chunks.
   Each chunk is handed to its own virtual thread, and the background thread resumes accumulating the next
   batch, never waiting on the network. Up to `MaxConcurrentTransmissions` requests (4 by default) run at
   the same time; beyond that, the background thread waits for a slot, so the downstream system is never
   overwhelmed.
5. A failed request's documents are put back in front of the batch and retried later instead of lost; a
   failing chunk retries independently of its siblings. Retries are delayed by an exponential backoff with
   jitter (`RetryBackoff`, up to `MaxRetryBackoff`), so a downstream system that is down, overloaded or
   rate-limiting is not hit again at whatever rate logs happen to arrive. Two safety nets bound how far this
   can go: while retries are held off, the pending batch never grows past `MaxPendingDocuments` (or
   `MaxPendingBytes`), its oldest documents being discarded past that point, and documents failing
   `MaxConsecutiveFailures` times in a row are discarded, so that one the downstream system never accepts is
   not retried forever.
   Both are reported through a `LogDeliveryListener` if configured (see [Observability](#observability)
   below), so this data loss is never silent.

Conversion and batching (step 3) always run on the one background thread, so your `LogDelivery`
implementation never needs to worry about concurrent calls to `send()`/`flush()`. Transmissions (step 4)
run concurrently on virtual threads, so `sendBulk()` (see below) must be safe to call concurrently with
itself if you keep mutable state; retried documents may also be delivered out of order, each carrying its
own timestamp.

These threads run with the class loader of your appender, or of your delivery, as their context class loader,
whatever thread starts the appender: a client finding its providers through it, as `ServiceLoader.load(Class)`
does, finds them in a web application or an executable JAR nesting its libraries as it does on a plain class
path. They take nothing else of the thread starting them, neither its inheritable thread-locals nor its own
context class loader, which they would keep reachable until the appender stops.

## Creating your own appender

The steps below are those of the [example appender](src/it/example-appender) of this repository, which sends
its documents to a collector listening on a TCP port: a complete project to start from, with its build, the
Log4j2 configuration of an application using it, and a test. The build of this library compiles it against
the JAR it has just packaged and runs that test, as a module then on the class path, so that what is shown
here works as it stands.

### 1. Configuration

Implement `LogConfiguration` to carry whatever settings your delivery mechanism needs, in addition to the
ones already required by the interface. It asks for only two things: what identifies the application
producing the logs (`Application`, `Host`, `Environment`, which end up inside every document), and a
`DeliveryOptions` carrying how the pipeline itself should behave — batching, backpressure, retries and
shutdown. Every `DeliveryOptions` setting has a usable default, so `getDeliveryOptions()` can be left alone
entirely until you have a reason to tune something.

The interface is read-only: build an immutable instance once, since background threads read it repeatedly
without synchronization. Log4j2's `@PluginBuilderFactory` pattern gives that shape naturally, its `Builder`
holding the mutable attributes and its `build()` returning the configuration, a record for instance (the
implementations linked above generate theirs with Lombok, through `@Value` and `@Builder`):

```java
public record ExampleConfiguration(String application, String host, String environment,
                                   DeliveryOptions deliveryOptions, String collectorHost, int collectorPort)
        implements LogConfiguration {

    @Override
    public String getApplication() {
        return application;
    }

    // ... and the same for getHost(), getEnvironment() and getDeliveryOptions()

}
```

The builder of the plugin builds them from the attributes of the element configuring the appender (see
[Appender](#3-appender)). Built in code, they only name what they tune:

```java
DeliveryOptions options = DeliveryOptions.builder()
        .flushThreshold(100)
        .flushInterval(Duration.ofSeconds(2))
        .build();
```

`getDeliveryOptions()` is read once, when the appender and its delivery are created: options changed later
take effect with the next appender, which a reconfiguration of Log4j2 creates. Use
`options.with().maxPendingDocuments(50_000).build()` to derive one instance from another without restating
the rest.

### 2. Delivery

Extend `AbstractBatchLogDelivery` and implement `sendBulk(List<Map<String, Object>>)`, returning a
`CompletableFuture<TransmissionResult<Map<String, Object>>>` completed with the outcome. Each call runs on a
virtual thread of its own, so a blocking client may complete the future before returning (as below), and an
asynchronous one later, from a thread of its own. The outcome is then handled on a thread of the library, so
that neither the listener nor a retry holds up the I/O thread of the client:

```java
public class ExampleLogDelivery extends AbstractBatchLogDelivery<ExampleConfiguration> {

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

    // ... toLine(document) writing a document the way the collector expects it

}
```

A request not answered within `TransmissionTimeout`, one minute by default, counts as failed: its slot is
freed and its documents are retried, the thread still inside `sendBulk` is interrupted, and the future it
returned is cancelled, an answer coming later being ignored. Give your client a shorter request timeout of its
own, whose failure says more about what happened. With neither, a request never answered holds one of the
`MaxConcurrentTransmissions` slots until the appender stops, and once such requests hold them all, the pipeline
stalls, its queue discarding the oldest events meanwhile.

On Java 21 to 23, a blocking client must not wait inside `synchronized` code, which keeps the virtual thread
on its carrier thread: with two processors, four requests waiting that way on a slow downstream system keep
every other virtual thread of the application from running for as long. Prefer an asynchronous client, or
one blocking through the locks of `java.util.concurrent`.

Every failed attempt is logged with its cause on Log4j2's status logger (see
[Shutdown and failure handling](#shutdown-and-failure-handling)), so there is no need to log it again. A
delivery logging anything of its own uses `StatusLogger.getLogger()` too, never an ordinary logger, which
would feed its own warnings back into the appender. The client library it calls logs through ordinary
loggers, which the configuration keeps away from the appender (see
[Shutdown and failure handling](#shutdown-and-failure-handling)).

The documents are handed over as an unmodifiable list. If your client can tell you that only *some* of
them were rejected (e.g. one malformed document in an otherwise-successful bulk response), return
`TransmissionResult.partialFailure(rejected, cause)` instead: only the rejected documents (matched back
against the ones you were given by reference identity, not `equals()`) are retried or eventually
discarded, while the rest are reported delivered.

To release resources (e.g. an HTTP client), override `close(Duration)`, calling `super.close(timeout)`
first so the current batch is flushed before they're released. It declares the `IOException` closing a client
usually throws, and no broader exception, which would cover an `InterruptedException` that `AutoCloseable`
advises against throwing:

```java
@Override
public void close(@Nullable Duration timeout) throws IOException {
    super.close(timeout);
    client.close();
}
```

### 3. Appender

Extend `AbstractLogDeliveryAppender` to wire everything together as a Log4j2 plugin, with a builder extending
`AbstractLogDeliveryAppender.Builder`, which Log4j2 fills from the attributes of the element configuring the
appender:

```java
@Plugin(name = "ExampleAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public class ExampleAppender extends AbstractLogDeliveryAppender<ExampleConfiguration> {

    protected ExampleAppender(Builder builder, ExampleConfiguration configuration) {
        super(builder, configuration);
    }

    @PluginBuilderFactory
    public static Builder newBuilder() {
        return new Builder();
    }

    @Override
    protected LogDelivery createLogDeliveryHandler() {
        return new ExampleLogDelivery(getLogConfiguration());
    }

    public static class Builder extends AbstractLogDeliveryAppender.Builder<ExampleAppender, Builder> {

        @PluginBuilderAttribute("CollectorHost")
        @Required(message = "No collector host provided")
        private String collectorHost;

        @PluginBuilderAttribute("CollectorPort")
        private int collectorPort = 5000;

        @Override
        public ExampleAppender build() {
            ExampleConfiguration configuration = new ExampleConfiguration(getApplication(), getHost(),
                    getEnvironment(), getDeliveryOptions(), collectorHost, collectorPort);
            return new ExampleAppender(this, configuration);
        }

        public Builder setCollectorHost(String collectorHost) {
            this.collectorHost = collectorHost;
            return this;
        }

        public Builder setCollectorPort(int collectorPort) {
            this.collectorPort = collectorPort;
            return this;
        }

    }

}
```

The builder of the library holds the attributes every appender takes: `name` and a `Filter` element,
`Application`, `Host` and `Environment`, `Converter` (see [Conversion](#5-conversion-optional)), and every
setting of the [configuration reference](#configuration-reference), durations in milliseconds. A setting left
unset keeps its default, the one of `DeliveryOptions`, or the one `defaultDeliveryOptions()` gives when the
builder of your plugin overrides it, to batch documents by default for instance. Your builder only declares
the attributes of its own, each with a public setter returning the builder: the annotation processor of recent
Log4j2 versions fails the compilation of a plugin without one. An exception thrown by `build()`, as
`DeliveryOptions` does for a value out of its range, is reported by Log4j2 on its status logger, the appender
being left out of the configuration.

Each event is then converted on the background thread, by a `DefaultLogConverter` unless the `Converter`
attribute names another one (see below), and the document sent to your delivery. Override
`createLogDeliveryTask(LogEvent)` to take over the whole task. The appender has no layout, the document being
what is sent, so its plugin builder declares no `Layout` element.

### 4. Build and configuration

Log4j2 finds a plugin through the `Log4j2Plugins.dat` file of its JAR, which its annotation processor writes
when compiling the plugin: without that file, the element naming the appender in a configuration is unknown.
Name the processor in the build, the compiler finding none by itself in a project with a module descriptor,
nor, from Java 23 on, in one without:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.apache.logging.log4j</groupId>
                <artifactId>log4j-core</artifactId>
                <version>${log4j.version}</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

A project generating code with Lombok names it there too, a path named there replacing the lookup of the
compiler rather than adding to it. On the module path, the project also declares the module shown under
[Installation](#installation).

An application then configures the appender by the name of its plugin, with the attributes of its builder:

```xml
<Configuration>
    <Appenders>
        <ExampleAppender name="Collector"
                         CollectorHost="logs.example.com"
                         CollectorPort="5000"
                         Application="shop"
                         Environment="production"
                         FlushThreshold="100"/>
    </Appenders>
    <Loggers>
        <Root level="info">
            <AppenderRef ref="Collector"/>
        </Root>
    </Loggers>
</Configuration>
```

An attribute takes its value from the environment through the lookups of Log4j2: `Application="${env:APP:-shop}"`
reads the variable `APP`, and falls back to `shop` while it is unset, as `${sys:app}` reads a system property.
`Host` defaults to what `${hostName}` gives: a property of that name the configuration defines, or else the
name of the machine as Log4j2 finds it (`InetAddress.getHostName()`), which may be its short name rather than its
canonical one.

The [test of the example](src/it/example-appender/src/test/java/com/example/appender/ExampleAppenderTest.java)
logs through such a configuration and checks what a collector of its own receives: a starting point for the
test of your appender.

### 5. Conversion (optional)

`DefaultLogConverter` already produces a sensible document for most use cases, with the following
fields:

| Field         | Description                                                                          |
|---------------|---------------------------------------------------------------------------------------|
| `datetime`    | ISO-8601 instant of the logging event in UTC, always with its milliseconds (`2026-09-28T10:15:30.000Z`) |
| `host`        | Taken from the configuration                                                         |
| `environment` | Taken from the configuration                                                         |
| `application` | Taken from the configuration                                                         |
| `logger`      | Logger name, or for an event without one the class of the logger (`getLoggerFqcn()`) |
| `level`       | Level of the logging event (e.g. `INFO`, `ERROR`)                                    |
| `logmessage`  | Formatted message, truncated to 32'768 characters by default (see `getMaxMessageLength()`) |
| `thread`      | Name of the thread that created the logging event                                    |
| `class`       | Name of the exception class, only present if the event carries an exception         |
| `stacktrace`  | Stack trace, only present with an exception, truncated to 8'192 characters by default (see `getMaxStackTraceLength()`) |

All MDC (context) entries are also added as-is, without overriding any of the fields above should a key
collide with one of them, even one left `null`. To customize this behaviour, extend `DefaultLogConverter`
and override the relevant `write*` method, or implement `LogConverter` from scratch for full control, then
name it in the `Converter` attribute of the appender, which creates it through its public constructor taking the
configuration (see `LogConverter.newInstance(className, configuration)`), and converts with the default one,
reporting why on the status logger, when it cannot. Override `getLogConverter()` of your appender instead to
create it otherwise, once rather than for every event. A converter returns a new document for every
event, never reused nor modified afterwards: the delivery keeps it until it is sent, retried or reported as
dropped. It runs on the background thread of the appender, and logs through `StatusLogger.getLogger()` if it
logs at all: an ordinary logger hands each of its messages back to the appender as one more event to convert.

A converter reading the location of the caller (`LogEvent.getSource()`) needs the appender to override
`requiresLocation()` to return `true`. The event is otherwise handed over without it: Log4j2 finds it by
walking the stack of the application thread, which costs twenty times the rest of `append()`.

The events the appender discards, when its queue is full, a stop gives up on them, your converter fails on
them or your delivery throws on them, still reach `onDocumentsDropped` (see [Observability](#observability)),
converted by a `DefaultLogConverter` whatever converter the appender uses: that one is safe to call from the
thread reporting them, and only gives up the fields it cannot read. A dead-letter sink then holds documents
of both shapes.

## Observability

To monitor the health of the delivery pipeline (throughput, latency, failures, data loss), pass a
`LogDeliveryListener` to your `LogDelivery` implementation, either through the constructor or via
`setListener(...)`:

```java
public class ExampleLogDelivery extends AbstractBatchLogDelivery<ExampleConfiguration> {

    public ExampleLogDelivery(ExampleConfiguration configuration) {
        super(configuration, new LogDeliveryListener() {
            @Override
            public void onTransmissionSucceeded(int documentCount, long durationMillis) {
                metrics.recordBatch(documentCount, durationMillis);
            }

            @Override
            public void onTransmissionFailed(int documentCount, Throwable cause) {
                metrics.recordFailure(documentCount);
            }

            @Override
            public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                metrics.recordDataLoss(documents.size(), reason);
                fallbackSink.save(documents); // e.g. write to a local file or a dead-letter queue
            }
        });
    }
    // ...
}
```

All three have a no-op default; override only what you need. They are called concurrently, from threads of
the library or the one stopping the appender, and from an application thread only for an event it logs once
the appender stopped, no other thread being left to report it: they must be thread-safe, and return quickly,
since a stop waits for its last notifications whatever its budget. A slow listener never holds up the
application: at most `MaxQueuedEvents` events discarded by a full queue wait to be reported, and those
discarded faster than that are only counted (see `getMetrics()` below) and logged. `onDocumentsDropped`
deserves the most attention in production — it fires only when documents are irrecoverably discarded (see
the safety nets above), which should normally never happen and is worth alerting on. It receives the actual
documents (an unmodifiable list), not just a count, as a last chance to save them.

Log what a listener observes through `StatusLogger.getLogger()`, as the library does (see
[Shutdown and failure handling](#shutdown-and-failure-handling)), never through an ordinary logger: its
messages would come back to the appender as documents, each failure logging one more to send, in a loop
sending requests as fast as the downstream system refuses them while neither batching nor `RetryBackoff` holds
them off.

For the most common case, saving every dropped document to a file so that nothing irrecoverably discarded is
lost outright, nest an appender of Log4j2 in the element of yours. The builder of the library takes it, and
your appender starts it, hands it each dropped document as an event whose message is a line of JSON wrapping
the document with the reason and a timestamp, and stops it once its own stop reported what it still held. A
`RollingFile` writing the message alone keeps newline-delimited JSON, rotated, compressed, with the permissions
and the retention it is given:

```xml
<ExampleAppender name="Collector" CollectorHost="logs.example.com" FlushThreshold="100">
    <RollingFile name="DeadLetters" fileName="/var/log/shop/dead-letters.ndjson"
                 filePattern="/var/log/shop/dead-letters-%d{yyyy-MM-dd}.ndjson.gz" filePermissions="rw-------">
        <PatternLayout pattern="%m%n"/>
        <TimeBasedTriggeringPolicy/>
        <DefaultRolloverStrategy>
            <Delete basePath="/var/log/shop" maxDepth="1">
                <IfFileName glob="dead-letters-*.ndjson.gz"/>
                <IfLastModified age="P30D"/>
            </Delete>
        </DefaultRolloverStrategy>
    </RollingFile>
</ExampleAppender>
```

Declared there rather than among the other appenders, it is started and stopped by yours alone: Log4j2 stops
the appenders of a configuration in no particular order, and could close the file before the last documents
reached it. Your appender adds it to the listener of its delivery, which receives the documents as well.
`DeadLetterListener` hands the dropped documents to an appender you give it in code, to combine with your own
listener (e.g. one that also records metrics) through `LogDeliveryListener.compose(...)`, which notifies each in
turn, one failing not keeping the others from being notified.

The `Failover` appender of Log4j2 cannot stand in for it: it switches to its failovers once the appender it
wraps throws, and this one never fails a call to `append()`, its transmissions failing later on threads of
their own.

Four methods also let you poll the current state of the pipeline directly, e.g. from a health indicator, an
alert or a metrics library, instead of only reacting to listener callbacks:

- `AbstractLogDeliveryAppender#getHealth()` — a `DeliveryHealth` telling what the pipeline does about its
  last transmission attempt: `UP` once it succeeded, `RETRYING` or `BACKING_OFF` after a failure (documents
  are not necessarily lost yet, being retried at the next opportunity or once the retry backoff elapses),
  and `DOWN` while the appender is not started, and for good if creating its delivery failed, every event then
  being discarded. It also tells how long ago documents were last accepted, so that an outage lasting too long
  is told apart from a single failed attempt.
- `AbstractLogDeliveryAppender#isBackpressured()` — `true` once the internal queue feeding the background
  thread (bounded by `MaxQueuedEvents`) is full, meaning the very next logging event would evict the oldest
  queued one — an early warning before `onDocumentsDropped` actually fires.
- `AbstractLogDeliveryAppender#getQueuedEventCount()` — how many events are currently in that queue, out of
  `MaxQueuedEvents`. The graduated version of the above: exported as a gauge, it shows the queue trending
  towards full while there is still time to react.
- `AbstractLogDeliveryAppender#getMetrics()` — a `DeliveryMetrics` snapshot of the whole pipeline: the events
  queued, the documents pending and their estimated size (under a byte limit), the requests in flight, as
  gauges, and the documents transmitted, the failed attempts and the documents dropped for whatever reason,
  those the queue discarded included, as counters that only ever grow.

```java
Gauge.builder("logs.pending", appender, a -> a.getMetrics().pendingDocuments()).register(registry);
FunctionCounter.builder("logs.dropped", appender, a -> a.getMetrics().droppedDocuments()).register(registry);
```

An alert can tolerate a short outage that retries absorb, and fire on one lasting longer:

```java
DeliveryHealth health = appender.getHealth();
boolean degraded = switch (health.status()) {
    case UP -> false;
    case RETRYING, BACKING_OFF -> health.sinceLastSuccess().compareTo(Duration.ofMinutes(5)) >= 0;
    case DOWN -> true;
};
```

Keep these signals out of the readiness and liveness probes of the application. An outage of the downstream
system would take every instance out of service at once, for logs it cannot ship, and the status stays
`RETRYING` after documents were given up on, until the next success: an instance taken out of service logs
nothing that could bring it back.

## Configuration reference

These settings are defined by `LogConfiguration` and its `DeliveryOptions`, and used regardless of the
delivery mechanism. The builder every appender plugin extends takes each of them as an attribute of the same
name, durations in milliseconds, along with `Converter`, the class name of the converter (see
[Conversion](#5-conversion-optional)):

| Setting         | Description                                                                                              |
|-----------------|-------------------------------------------------------------------------------------------------------------|
| `Application`   | Name of the application generating the logs, included in every document. Defaults to `unknown`. |
| `Host`          | Name of the host the application is running on, included in every document. Defaults to what `${hostName}` gives in the configuration of Log4j2, the name of the machine unless the configuration defines it (see [Build and configuration](#4-build-and-configuration)). |
| `Environment`   | Name of the environment the application is running in, included in every document. Defaults to `local`. |
| `FlushThreshold`| Number of accumulated documents that triggers a transmission. `1` sends every log individually (no batching). Defaults to `1`, an appender shipping logs over the network normally raising it. |
| `FlushInterval` | Time between two periodic flushes, so that documents aren't delayed indefinitely under low traffic: a batch under `FlushThreshold`, documents stacked while `RetryBackoff` holds off transmissions, and those a failed transmission put back, all waiting for the next log otherwise. Defaults to `5s`; at least `1ms`. |
| `FlushLevel` | Minimum severity (e.g. `WARN`) that triggers an immediate flush of the whole current batch right after that event, instead of waiting for `FlushThreshold`/`FlushInterval`. Defaults to unset, disabling this behavior. |
| `MaxConcurrentTransmissions` | Maximum number of requests transmitted at the same time (see step 4 above). Defaults to `4`. |
| `TransmissionTimeout` | Maximum time a request waits for its answer before it counts as failed: its slot is freed and its documents are retried, an answer coming later being ignored (see [Delivery](#2-delivery)). Kept above the request timeout of the client, whose own failure says more. Defaults to `1min`; `null`, or `0` as an attribute, waits indefinitely. |
| `MaxPendingDocuments` | Maximum documents a pending batch can hold in memory: reaching it sends the batch as `FlushThreshold` does, and while retries are held off, the oldest are discarded past it (see step 5 above). Independent of `MaxRequestDocuments`. Defaults to `10'000`. |
| `MaxRequestDocuments` | Maximum documents sent together in a single request; a bigger batch is split into chunks of that size (see step 4 above), each retried independently. Defaults to unbounded (no chunking); set to match a downstream system's per-request limit. |
| `MaxPendingBytes` | Maximum estimated size a pending batch can hold in memory, which it bounds exactly as `MaxPendingDocuments` does by count. Defaults to unbounded. |
| `MaxRequestBytes` | Maximum estimated size of a single request; a heavier batch is split into chunks that each stay under it. Usually the limit that actually matters — Elasticsearch and OpenSearch reject a bulk over `http.max_content_length` by payload size, not document count. Defaults to unbounded. |
| `MaxConsecutiveFailures` | Maximum consecutive failed attempts for the same documents before they are discarded instead of retried again (see step 5 above). Defaults to `5`; `2147483647` retries for good in practice. |
| `MaxQueuedEvents` | Maximum logging events queued awaiting conversion and batching by the background thread; the oldest is discarded past that to make room for a new one. Defaults to `10'000`. |
| `RetryBackoff` | Base delay before retrying a failed transmission, doubling per consecutive failure with full jitter, and holding off newly accumulated documents too while in effect. Defaults to `1s`; `0` disables it, retrying at the next opportunity. |
| `MaxRetryBackoff` | Cap the delay above never grows past. Defaults to `30s`. |
| `CloseTimeout` | Maximum time the delivery waits, while closing, for in-flight transmissions to complete before giving up and reporting what's still pending as dropped. Bounds how long an unresponsive downstream system can hold up shutdown. While Log4j2's `shutdownTimeout` is left at `0`, also the budget of the whole appender stop, already-accepted events included, to keep under the time the platform waits before killing the process (see [Shutdown](#shutdown-and-failure-handling)). Defaults to `5s`; `null` waits indefinitely. |

`Application`, `Host` and `Environment` come from `LogConfiguration` itself; everything else is a component
of the `DeliveryOptions` it returns, all with usable defaults.

Every setting is validated when `DeliveryOptions` is built: a count or size below `1`, a negative duration, or
a `FlushInterval` or `TransmissionTimeout` under a millisecond throws an `IllegalArgumentException` naming it, so a misconfiguration
fails the Log4j2 configuration rather than the delivery once it runs. A limit meant to be unbounded takes the
largest value of its type, as its default does.

### Bounding by size rather than by count

`MaxPendingDocuments` and `MaxRequestDocuments` count documents, which only stands in for what actually
matters — memory held, and bytes put on the wire — while documents are of roughly uniform size. Setting
`MaxPendingBytes` or `MaxRequestBytes` bounds those directly instead:

```java
DeliveryOptions.builder()
        .maxRequestBytes(50L * 1024 * 1024)  // Comfortably under Elasticsearch's 100MB default
        .maxPendingBytes(64L * 1024 * 1024)  // Cap what a struggling backend can pin in memory
        .build();
```

Sizes come from `AbstractBatchLogDelivery#estimateDocumentSize(Map)`, which approximates a compact UTF-8
JSON rendering of the document. Override it when your transport knows better (an Elasticsearch bulk body
adds an action line per document; a compressed or binary payload is smaller than its JSON form). It is
never called while both limits are left unbounded, so a delivery that does not use them pays nothing for
measuring. A document larger than `MaxRequestBytes` on its own is still sent alone, since there is no way
to split it further.

## Shutdown and failure handling

- **Shutdown:** stopping the appender stops accepting new logs, waits for already-accepted ones to be
  processed, then flushes and closes the delivery handler, waiting for that final transmission too, so logs
  emitted just before shutdown aren't lost. Both waits share one budget: the `shutdownTimeout` attribute (in
  milliseconds) of the root `<Configuration>` element of your Log4j2 configuration or, while it is left at
  its default of `0` (also what a reconfiguration passes), `CloseTimeout`. The delivery is closed within
  what the accepted logs left of it, so a downstream system that accepts a connection and then never
  answers can't hold up the JVM past that budget. Anything still pending once it is spent — events still
  queued or waiting to be reported, a batch still waiting for a transmission slot, a failing final
  transmission's documents, or documents a stuck transmission never gave back — is reported through
  `onDocumentsDropped` before the stop returns, rather than discarded along with the appender, so a dead-letter
  sink still gets its last chance at them. A transmission answering after that
  may still deliver its documents, which a dead-letter sink then holds as well. That report comes once the
  budget is spent, so keep the budget under the time the platform lets a stopping process run before killing
  it, less what the application does before Log4j2 stops: `docker stop` kills after 10 seconds, Kubernetes
  after 30 by default, and the default `CloseTimeout` of 5 seconds stays under both. Log4j2 stops its
  appenders one after the other, each with the whole budget, and the budgets of several appenders add
  up: two of them with the default take 10 seconds during an outage, so it is their sum that must stay under
  the kill. A process killed while the stop still waits reports none of what is pending.
- **Failures:** if `sendBulk` completes its future with `TransmissionResult.failure(...)` (or throws, or
  the future completes exceptionally, or not within `TransmissionTimeout`), the batch is kept and retried later
  instead of lost, up to the
  safety limits above; a `TransmissionResult.partialFailure(...)` keeps and retries only the rejected
  documents. Delivery is thus at least once: a request the downstream system processed although its answer
  failed, the client timing out first for instance, is retried, and its documents delivered twice. Any
  exception thrown while converting or sending a log is reported through Log4j2's `StatusLogger` (visible
  with `-Dlog4j2.debug`, a `status` attribute on the root `<Configuration>` element, or a
  `<StatusListener>`) instead of being silently swallowed, in addition to your
  `LogDeliveryListener` if configured. Every failed attempt is logged there as a warning with its cause,
  whether returned or thrown, and documents given up on as an error, so an outage shows from its first
  failure rather than once documents start being discarded.
- **Its own diagnostics:** everything this library reports about itself goes to that status channel and
  never through an ordinary logger. That is deliberate rather than incidental: an appender is normally
  attached to the root logger, so a warning logged the ordinary way comes straight back as one more
  document to ship, whose own failure warns again — a loop that starts precisely when the downstream
  system goes down, and does not settle. So don't route the library's own loggers anywhere: there is
  nothing to route.
- **Loops through other loggers:** code running inside the pipeline that logs through an ordinary logger
  feeds the appender just the same, and a single event starts the loop. A converter logging each event it
  converts sustains itself as fast as the background thread runs, and a client library logging each
  request at a level the appender receives, the root logger at `DEBUG` for instance, as fast as requests
  complete. Converters, deliveries and listeners log through `StatusLogger.getLogger()`, and the loggers of
  the client a delivery calls are kept away from the appender: a `<Logger>` for their package with
  `additivity="false"` and another appender, or a level above the one they log requests at.

## Example implementations

- The [example appender](src/it/example-appender) of this repository, minimal and complete
- [Elasticsearch Appender](https://github.com/chavaillaz/elasticsearch-log4j-appender)
- [OpenSearch Appender](https://github.com/chavaillaz/opensearch-log4j-appender)

## Contributing

A feature request or a bug report is welcome as an issue, and a change as a pull request from a fork.

The build needs **JDK 21 or later** and Maven 3.8 or later, and says so on an older one. Everything it checks
runs with:

```shell
mvn verify
```

A change is ready once that passes, which takes:

- **Tests covering it.** They assert with [AssertJ](https://assertj.github.io/doc/), and each is named by a
  sentence saying what the library does (its `@DisplayName`). [scenarios.md](scenarios.md) lists those
  sentences, along with the scenarios deliberately left as they are: a test added, renamed or removed changes
  that list too.
- **Imports in order**, which Checkstyle verifies (see [checkstyle.xml](checkstyle.xml)): the static ones,
  then those of `java`, `javax` and `jakarta`, then those of third parties, then those of `com.chavaillaz`,
  each group sorted alphabetically and separated from the next by a blank line, with no star import and none
  left unused.
- **No compiler warning.** Every check of the compiler is on (`-Xlint:all`), and a warning fails the build on
  JDK 21. A later JDK only shows the warnings of the checks it adds.
- **Dependency versions converging.** Two dependencies asking for different versions of a third one, the test
  ones included, fail the build, rather than Maven taking whichever it reaches first: the version is then managed
  in the POM, the newest one asked for unless something rules it out.
- **The example appender working.** Once the JAR is packaged, the build compiles the
  [example appender](src/it/example-appender) against it and runs its test, as a module then on the class path:
  what an appender built on the library goes through, where the tests only see the classes the JAR is made of.
  A change to what this page shows of the API changes the example too. Its output is in
  `target/it/example-appender/build.log`, and `-DskipTests` skips it along with the tests.

Every pull request, and every push to `master`, is built that way on Java 21 and on the latest long-term
support version, with the coverage of the tests measured, and failing once they cover less than 80% of the lines
or of the branches of the library: `mvn clean -Pcoverage verify` writes the same report to `target/site/jacoco`,
the `clean` keeping the data of an earlier build out of it. One more build runs the tests and the example on the
oldest Log4j2 version supported, with the library compiled as it is released, which `mvn compile` followed by
`mvn verify -Dmaven.main.skip -Dlog4j.version=2.24.0` does too.

Mutation testing tells how much of what the tests run they check: `mvn -Pmutation test` has
[PIT](https://pitest.org) alter the compiled classes one change at a time, a condition negated or a value
returned replaced for instance, and run the tests covering each change, one of which should fail. The build fails
once the tests catch less than 87% of the changes, 90% of them being caught today. The report, in
`target/pit-reports`, lists the changes no test caught, either worth a test or making no difference a test could
see. A run takes about twenty minutes with `-Dpit.threads=4`, which tries four changes at once rather than two,
and `-DtargetClasses=com.chavaillaz.appender.internal.Batcher` limits it to one class. `LoadTest` is left out,
its results depending on timing.
A [workflow](.github/workflows/mutation-testing.yml) runs it every Monday, and on demand, keeping the report of
each run.

### Releasing

A release is made by the maintainer, from a clean working tree: `sh release.sh` asks for the version, sets it
in the POM and in the dependency this page shows, and pushes that commit as a tag of the same name. `master`
keeps its snapshot version, and takes the README naming the new version in a commit of its own, to push with
the next ones.
Publishing a GitHub release for that tag then starts the [workflow](.github/workflows/maven-publish.yml)
building, signing and publishing the artifacts to Maven Central.

## License

This project is under Apache 2.0 License.
