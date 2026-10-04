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

Requires **Java 21 or later** (virtual threads are used internally to transmit batches).

The dependency is available in maven central (see badge for version):

```xml
<dependency>
    <groupId>com.chavaillaz</groupId>
    <artifactId>common-log4j-appender</artifactId>
</dependency>
```

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

## Creating your own appender

### 1. Configuration

Implement `LogConfiguration` to carry whatever settings your delivery mechanism needs, in addition to the
ones already required by the interface. It asks for only two things: what identifies the application
producing the logs (`Application`, `Host`, `Environment`, which end up inside every document), and a
`DeliveryOptions` carrying how the pipeline itself should behave — batching, backpressure, retries and
shutdown. Every `DeliveryOptions` setting has a usable default, so `getDeliveryOptions()` can be left alone
entirely until you have a reason to tune something.

The interface is read-only: build an immutable instance once, since background threads read it repeatedly
without synchronization. Log4j2's `@PluginBuilderFactory` pattern gives that shape naturally, its `Builder`
holding the mutable attributes and its `build()` returning the configuration:

```java
@Value
@Builder
public class MyConfiguration implements LogConfiguration {
    String application;
    String host;
    String environment;
    @Builder.Default // Rather than null when the builder leaves it unset
    DeliveryOptions deliveryOptions = DeliveryOptions.defaults();
    String url; // Anything else specific to your delivery mechanism
}
```

Your plugin `Builder` assembles the options from its own attributes, and only needs to name the ones it
actually exposes:

```java
DeliveryOptions options = DeliveryOptions.builder()
        .flushThreshold(flushThreshold)
        .flushInterval(flushInterval)
        .build();
```

`getDeliveryOptions()` is read on the path taken by every document, not once at construction, so an
implementation can vary a limit at runtime by returning a different instance — return one held in a
`volatile` field rather than building a new one on every call. Use
`options.with().maxPendingDocuments(50_000).build()` to derive one instance from another without restating
the rest. A few settings are read only once, when the appender or its delivery is created:
`FlushInterval`, `MaxConcurrentTransmissions`, `MaxQueuedEvents`, `RetryBackoff` and `MaxRetryBackoff`.

### 2. Delivery

Extend `AbstractBatchLogDelivery` and implement `sendBulk(List<Map<String, Object>>)`, returning a
`CompletableFuture<TransmissionResult<Map<String, Object>>>` completed with the outcome. Each call runs on a
virtual thread of its own, so a blocking client may complete the future before returning (as below), and an
asynchronous one later, from a thread of its own. The outcome is then handled on a thread of the library, so
that neither the listener nor a retry holds up the I/O thread of the client:

```java
public class MyLogDelivery extends AbstractBatchLogDelivery<MyConfiguration> {

    public MyLogDelivery(MyConfiguration configuration) {
        super(configuration);
    }

    @Override
    protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
        try {
            myClient.send(documents);
            return CompletableFuture.completedFuture(TransmissionResult.success());
        } catch (Exception e) {
            // Logged with its cause and retried by the library
            return CompletableFuture.completedFuture(TransmissionResult.failure(e));
        }
    }
}
```

Either way the future must complete: a transmission holds one of the `MaxConcurrentTransmissions` slots until
it does, and once unanswered requests hold them all, the pipeline stalls until the appender stops, its queue
discarding the oldest events meanwhile. Give your client a request timeout.

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
first so the current batch is flushed before they're released.

### 3. Appender

Extend `AbstractLogDeliveryAppender` to wire everything together as a Log4j2 plugin:

```java
@Plugin(name = "MyAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public class MyAppender extends AbstractLogDeliveryAppender<MyConfiguration> {

    protected MyAppender(String name, Filter filter, MyConfiguration configuration) {
        super(name, filter, configuration);
    }

    @Override
    protected LogDelivery createLogDeliveryHandler() {
        return new MyLogDelivery(getLogConfiguration());
    }

    // A @PluginBuilderFactory Builder is also needed to expose the configuration as XML/properties/...
    // attributes; see the linked implementations for a complete example.
}
```

Each event is then converted by a `DefaultLogConverter` on the background thread, and the document sent to
your delivery. Override `getLogConverter()` to convert with another one (see below), or
`createLogDeliveryTask(LogEvent)` to take over the whole task. The appender has no layout, the document being
what is sent, so its plugin builder declares no `Layout` element.

### 4. Conversion (optional)

`DefaultLogConverter` already produces a sensible document for most use cases, with the following
fields:

| Field         | Description                                                                          |
|---------------|---------------------------------------------------------------------------------------|
| `datetime`    | ISO-8601 timestamp of the logging event                                              |
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
collide with one of them. To customize this behaviour, extend `DefaultLogConverter` and override the
relevant `write*` method, or implement `LogConverter` from scratch for full control, then return it from
`getLogConverter()` of your appender. A converter takes the configuration it needs in its constructor:
`LogConverter.newInstance(className, configuration)` creates the one a configuration names by its class,
through its public constructor taking the configuration. A converter runs on the background thread of the
appender, and logs through `StatusLogger.getLogger()` if it logs at all: an ordinary logger hands each of
its messages back to the appender as one more event to convert.

A converter reading the location of the caller (`LogEvent.getSource()`) needs the appender to override
`requiresLocation()` to return `true`. The event is otherwise handed over without it: Log4j2 finds it by
walking the stack of the application thread, which costs twenty times the rest of `append()`.

## Observability

To monitor the health of the delivery pipeline (throughput, latency, failures, data loss), pass a
`LogDeliveryListener` to your `LogDelivery` implementation, either through the constructor or via
`setListener(...)`:

```java
public class MyLogDelivery extends AbstractBatchLogDelivery<MyConfiguration> {

    public MyLogDelivery(MyConfiguration configuration) {
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

`FileDeadLetterListener` provides a ready-made `onDocumentsDropped` for the most common case: it appends
every dropped document to a local file, one JSON object per line (newline-delimited JSON), wrapped with the
reason and a timestamp, so nothing irrecoverably discarded is lost outright. Use it as-is, or combine it
with your own listener (e.g. one that also records metrics) through `LogDeliveryListener.compose(...)`,
which notifies each in turn, one failing not keeping the others from being notified:

```java
LogDeliveryListener.compose(
        new MetricsListener(),
        new FileDeadLetterListener(Path.of("/var/log/myapp/dead-letters.ndjson")));
```

The file rotates every day, the date being inserted before its extension: the documents dropped on 28
September 2026 go to `dead-letters-2026-09-28.ndjson`, so that each day can be replayed, archived or deleted
on its own. A day starts at midnight in the default time zone of the system, or in the one of the `Clock`
passed as second argument. Several appenders, or applications on the same host, can share the file on a local
disk: each line is appended whole, never cut by the lines of another.

Four methods also let you poll the current state of the pipeline directly, e.g. from a readiness
endpoint or a metrics library, instead of only reacting to listener callbacks:

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

A readiness check can tolerate a short outage that retries absorb, and report one lasting longer:

```java
DeliveryHealth health = appender.getHealth();
boolean ready = switch (health.status()) {
    case UP -> true;
    case RETRYING, BACKING_OFF -> health.sinceLastSuccess().compareTo(Duration.ofMinutes(5)) < 0;
    case DOWN -> false;
};
```

## Configuration reference

These settings are defined by `LogConfiguration` and its `DeliveryOptions`, and used regardless of the
delivery mechanism (each appender implementation typically exposes them as its own plugin attributes, see
its documentation):

| Setting         | Description                                                                                              |
|-----------------|-------------------------------------------------------------------------------------------------------------|
| `Application`   | Name of the application generating the logs, included in every document.                                    |
| `Host`          | Name of the host the application is running on, included in every document.                                 |
| `Environment`   | Name of the environment the application is running in, included in every document.                         |
| `FlushThreshold`| Number of accumulated documents that triggers a transmission. `1` sends every log individually (no batching). Defaults to `1`, an appender shipping logs over the network normally raising it. |
| `FlushInterval` | Time between two periodic flushes, so that documents aren't delayed indefinitely under low traffic: a batch under `FlushThreshold`, documents stacked while `RetryBackoff` holds off transmissions, and those a failed transmission put back, all waiting for the next log otherwise. Defaults to `5s`; at least `1ms`. |
| `FlushLevel` | Minimum severity (e.g. `WARN`) that triggers an immediate flush of the whole current batch right after that event, instead of waiting for `FlushThreshold`/`FlushInterval`. Defaults to unset, disabling this behavior. |
| `MaxConcurrentTransmissions` | Maximum number of requests transmitted at the same time (see step 4 above). Defaults to `4`. |
| `MaxPendingDocuments` | Maximum documents a pending batch can hold in memory: reaching it sends the batch as `FlushThreshold` does, and while retries are held off, the oldest are discarded past it (see step 5 above). Independent of `MaxRequestDocuments`. Defaults to `10'000`. |
| `MaxRequestDocuments` | Maximum documents sent together in a single request; a bigger batch is split into chunks of that size (see step 4 above), each retried independently. Defaults to unbounded (no chunking); set to match a downstream system's per-request limit. |
| `MaxPendingBytes` | Maximum estimated size a pending batch can hold in memory, which it bounds exactly as `MaxPendingDocuments` does by count. Defaults to unbounded. |
| `MaxRequestBytes` | Maximum estimated size of a single request; a heavier batch is split into chunks that each stay under it. Usually the limit that actually matters — Elasticsearch and OpenSearch reject a bulk over `http.max_content_length` by payload size, not document count. Defaults to unbounded. |
| `MaxConsecutiveFailures` | Maximum consecutive failed attempts for the same documents before they are discarded instead of retried again (see step 5 above). Defaults to `5`; `2147483647` retries for good in practice. |
| `MaxQueuedEvents` | Maximum logging events queued awaiting conversion and batching by the background thread; the oldest is discarded past that to make room for a new one. Defaults to `10'000`. |
| `RetryBackoff` | Base delay before retrying a failed transmission, doubling per consecutive failure with full jitter, and holding off newly accumulated documents too while in effect. Defaults to `1s`; `0` disables it, retrying at the next opportunity. |
| `MaxRetryBackoff` | Cap the delay above never grows past. Defaults to `30s`. |
| `CloseTimeout` | Maximum time the delivery waits, while closing, for in-flight transmissions to complete before giving up and reporting what's still pending as dropped. Bounds how long an unresponsive downstream system can hold up shutdown. While Log4j2's `shutdownTimeout` is left at `0`, also the budget of the whole appender stop, already-accepted events included (see [Shutdown](#shutdown-and-failure-handling)). Defaults to `30s`; `null` waits indefinitely. |

`Application`, `Host` and `Environment` come from `LogConfiguration` itself; everything else is a component
of the `DeliveryOptions` it returns, all with usable defaults. An appender implementation typically exposes
only some of them as plugin attributes — the rest stay available through `DeliveryOptions.builder()` in your
own configuration class, whether or not the appender surfaces them.

Every setting is validated when `DeliveryOptions` is built: a count or size below `1`, a negative duration or
a `FlushInterval` under a millisecond throws an `IllegalArgumentException` naming it, so a misconfiguration
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
  queued or waiting to be reported, a failing final transmission's documents, or documents a stuck
  transmission never gave back — is reported through `onDocumentsDropped` rather than discarded along with
  the appender, so a dead-letter sink still gets its last chance at them. A transmission answering after that
  may still deliver its documents, which a dead-letter sink then holds as well.
- **Failures:** if `sendBulk` completes its future with `TransmissionResult.failure(...)` (or throws, or
  the future completes exceptionally), the batch is kept and retried later instead of lost, up to the
  safety limits above; a `TransmissionResult.partialFailure(...)` keeps and retries only the rejected
  documents. Any exception thrown while converting or sending a log is reported through Log4j2's
  `StatusLogger` (visible with `-Dlog4j2.debug`, a `status` attribute on the root `<Configuration>`
  element, or a `<StatusListener>`) instead of being silently swallowed, in addition to your
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

- [Elasticsearch Appender](https://github.com/chavaillaz/elasticsearch-log4j-appender)
- [OpenSearch Appender](https://github.com/chavaillaz/opensearch-log4j-appender)

## Contributing

If you have a feature request or found a bug, you can:

- Write an issue
- Create a pull request

If you want to contribute then

- Please write tests covering all your changes
- Ensure you didn't break the build by running `mvn test`
- Fork the repo and create a pull request

## License

This project is under Apache 2.0 License.
