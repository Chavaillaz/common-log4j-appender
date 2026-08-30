# Common Log4j Appender

![Quality Gate](https://github.com/chavaillaz/common-log4j-appender/actions/workflows/sonarcloud.yml/badge.svg)
![Dependency Check](https://github.com/chavaillaz/common-log4j-appender/actions/workflows/snyk.yml/badge.svg)
[![Maven Central](https://maven-badges.sml.io/maven-central/com.chavaillaz/common-log4j-appender/badge.svg?subject=Maven%20Central&color=blue)](https://maven-badges.sml.io/maven-central/com.chavaillaz/common-log4j-appender)
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

## How it works

Three concepts are involved, each with a corresponding interface/class to implement:

| Concept       | Type to extend/implement       | Responsibility                                                                 |
|---------------|---------------------------------|---------------------------------------------------------------------------------|
| Appender      | `AbstractLogDeliveryAppender`   | Plugs into Log4j2, receives events and hands them off to the background thread |
| Delivery      | `AbstractBatchLogDelivery`      | Accumulates converted documents and sends them by batches                      |
| Conversion    | `LogConverter`                  | Turns a `LogEvent` into a `Map<String, Object>` document to be sent            |
| Configuration | `LogConfiguration`              | Carries the settings shared by the three components above                      |

The flow for a single log event is:

1. Log4j2 calls `append(LogEvent)` on your appender (on the application thread).
2. The event is immediately converted to an immutable snapshot and submitted as a task to a single
   background thread. `append()` returns right away: the application thread never waits on this task.
3. On the background thread, the task converts the event to a document (using your `LogConverter`) and
   hands it to your `LogDelivery`, which stacks it into the current batch.
4. Once the batch reaches `FlushThreshold` documents, or `FlushInterval` has elapsed since the last
   transmission (whichever happens first), it is due for transmission: if it holds more than
   `MaxRequestDocuments` documents, it is first split into consecutive chunks of at most that size (by default,
   `MaxRequestDocuments` is unbounded and nothing is split). Each chunk is then handed off to its own virtual
   thread, and the background thread immediately resumes accumulating the next batch: it never waits for a
   network call to complete. Up to 4 requests (whole batches or individual chunks) can be transmitted
   concurrently this way; beyond that, the background thread waits for one to complete before handing off
   the next one, so the downstream system is never overwhelmed with unbounded concurrent requests.
5. If a request fails, its documents are put back in front of the current batch and retried at the next
   opportunity instead of being lost, without hammering the external system with one attempt per incoming
   message in the meantime; a chunk retries independently of any sibling chunks from the same original
   batch, so one failing never holds up or duplicates the others. Two safety nets bound how far this can go:
   a batch is never allowed to grow past `MaxPendingDocuments` in memory (the oldest ones are discarded past
   that point), and a request that fails `MaxConsecutiveFailures` times in a row is discarded entirely
   instead of being retried forever, which would otherwise happen if a single document can never be
   accepted by the downstream system. Both cases are reported through a `LogDeliveryListener` if one is
   configured (see [Observability](#observability) below), so this data loss is never silent.

Steps 2 and 3 (batching) always happen on the same single background thread, so your `LogDelivery`
implementation never needs to worry about concurrent calls to `send()`/`flush()`. Step 4 (transmission)
may run concurrently across several virtual threads, so `sendBulk()` (see below) must be safe to run
concurrently with itself if you keep any mutable state of your own in your `LogDelivery` implementation;
retried batches may also be redelivered in a different relative order after a failure, which is usually
fine given each document already carries its own timestamp.

## Creating your own appender

### 1. Configuration

Implement `LogConfiguration` (or extend an existing implementation) to carry whatever settings your
delivery mechanism needs, in addition to the ones already required by the interface:

```java
public class MyConfiguration implements LogConfiguration {
    private String application;
    private String host;
    private String environment;
    private long flushThreshold;
    private Duration flushInterval;
    private LogConverter converter; // Not part of LogConfiguration itself, but used below
    private String url; // Anything else specific to your delivery mechanism
    // Getters and setters...
}
```

### 2. Delivery

Extend `AbstractBatchLogDelivery` and implement `sendBulk(List<Map<String, Object>>)`, returning
`true` on success and `false` on failure (so the batch is retried later instead of being dropped):

```java
public class MyLogDelivery extends AbstractBatchLogDelivery<MyConfiguration> {

    public MyLogDelivery(MyConfiguration configuration) {
        super(configuration);
    }

    @Override
    protected boolean sendBulk(List<Map<String, Object>> documents) {
        try {
            myClient.send(documents);
            return true;
        } catch (Exception e) {
            log.warn("Unable to send bulk of {} documents", documents.size(), e);
            return false;
        }
    }
}
```

If you need to release resources (close an HTTP client, ...), override `close()`, calling
`super.close()` first so that the current batch is flushed before your resources are released.

### 3. Appender

Extend `AbstractLogDeliveryAppender` to wire everything together as a Log4j2 plugin:

```java
@Plugin(name = "MyAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public class MyAppender extends AbstractLogDeliveryAppender<MyConfiguration> {

    protected MyAppender(String name, Filter filter, Layout<?> layout, MyConfiguration configuration) {
        super(name, filter, layout, configuration);
    }

    @Override
    public LogDelivery createLogDeliveryHandler() {
        return new MyLogDelivery(getLogConfiguration());
    }

    @Override
    public Runnable createLogDeliveryTask(LogEvent loggingEvent) {
        // loggingEvent is already an immutable snapshot, safe to use from the background thread
        LogConverter converter = getLogConfiguration().getConverter();
        return () -> Optional.ofNullable(getLogDeliveryHandler())
                .ifPresent(handler -> handler.send(converter.convert(loggingEvent)));
    }

    // A @PluginBuilderFactory Builder is also needed to expose the configuration as XML/properties/...
    // attributes; see the linked implementations for a complete example.
}
```

### 4. Conversion (optional)

`DefaultLogConverter` already produces a sensible document for most use cases, with the following
fields:

| Field         | Description                                                                          |
|---------------|---------------------------------------------------------------------------------------|
| `datetime`    | ISO-8601 timestamp of the logging event                                              |
| `host`        | Taken from the configuration                                                         |
| `environment` | Taken from the configuration                                                         |
| `application` | Taken from the configuration                                                         |
| `logger`      | Fully qualified caller class name, falling back to the logger name if not available  |
| `level`       | Level of the logging event (e.g. `INFO`, `ERROR`)                                    |
| `logmessage`  | Formatted message of the logging event                                               |
| `thread`      | Name of the thread that created the logging event                                    |
| `class`       | Canonical name of the exception class, only present if the event carries an exception |
| `stacktrace`  | Stack trace of the exception, only present if the event carries an exception, truncated to 8'192 characters by default (see `getMaxStackTraceLength()`) so that a deep or recursive cause chain cannot inflate a document (and by extension a whole batch) on its own |

All MDC (context) entries are also added as-is, without overriding any of the fields above should a key
collide with one of them. To customize this behaviour, extend `DefaultLogConverter` and override the
relevant `write*` method, or implement `LogConverter` from scratch for full control.

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

All three methods have a no-op default implementation, so you only need to override the ones you are
interested in. `onDocumentsDropped` is the one to pay the most attention to in production: it is only
called when documents are irrecoverably discarded (see the safety nets described above), which should
normally never happen and is worth alerting on. Unlike the other two methods, it receives the actual
documents (as an unmodifiable list) rather than just a count, since this is meant as a last chance to save
them from being lost entirely.

## Configuration reference

These settings are defined by `LogConfiguration` and used regardless of the delivery mechanism (each
appender implementation typically exposes them as its own plugin attributes, see its documentation):

| Setting         | Description                                                                                              |
|-----------------|-------------------------------------------------------------------------------------------------------------|
| `Application`   | Name of the application generating the logs, included in every document.                                    |
| `Host`          | Name of the host on which the application is running, included in every document.                          |
| `Environment`   | Name of the environment in which the application is running, included in every document.                   |
| `FlushThreshold`| Number of accumulated documents that triggers a transmission. Use `1` to send every log individually (no batching), or a higher value to reduce the number of requests sent to the external system. |
| `FlushInterval` | Maximum time between two transmissions: even a partially filled batch is sent once this interval elapses, so logs are not delayed indefinitely under low traffic. Only relevant when `FlushThreshold` is greater than `1`. |
| `MaxConcurrentTransmissions` | Maximum number of requests (whole batches or individual chunks) transmitted at the same time (see step 4 above). Defaults to `4`; raise it for a downstream system that can absorb more concurrent requests, or lower it to as little as `1` to strictly serialize transmissions (a value below `1` is rejected, as it would leave every transmission waiting forever). |
| `MaxPendingDocuments` | Maximum number of documents a pending batch can hold in memory while waiting to be (re)transmitted, discarding the oldest ones past that point (see step 5 above). This bounds memory only, independent of how many documents end up in a single request; see `MaxRequestDocuments` for that. Defaults to `10'000`; `0` or a negative value discards every document as soon as it would be added, effectively disabling this delivery. |
| `MaxRequestDocuments` | Maximum number of documents sent together in a single request. A pending batch bigger than this is split into chunks of at most this size before transmission (see step 4 above), each retried independently on failure. Defaults to unbounded (no chunking); set it to match a downstream system's own per-request limit. |
| `MaxConsecutiveFailures` | Maximum number of consecutive failed attempts allowed for the same request (a whole batch, or one chunk of it) before it is discarded instead of retried again (see step 5 above). Defaults to `5`; `0` or a negative value disables this limit and retries indefinitely instead. |
| `MaxQueuedEvents` | Maximum number of logging events that can be queued, waiting to be converted and batched by the background thread, before new ones are discarded instead of being accepted (see below). Defaults to `10'000`. |

These five are implemented as default methods on `LogConfiguration` itself (rather than plugin
attributes), so they are available even if the appender implementation you use does not explicitly expose
them as configuration; ask its maintainer to wire them up, or override them in your own configuration
class in the meantime.

## Performance and reliability notes

- **Constant-time event queueing:** events accepted by `append()` are handed to the background thread
  through a plain FIFO queue (constant time to enqueue/dequeue), not a scheduled executor's queue (which
  would be logarithmic time, since it keeps tasks ordered by delay); the periodic flush trigger runs on a
  separate, otherwise idle, thread that simply hands a flush over to that same FIFO queue, so this doesn't
  come at the cost of the ordering guarantees described above.
- **Bounded queue:** that queue is capped at `MaxQueuedEvents` (10'000 by default) so it cannot grow
  memory usage without limit if the external system stays unavailable for a long time (once every
  `MaxConcurrentTransmissions` slot is stuck, events keep arriving faster than they can be processed).
  `append()` still never blocks: once the limit is reached, further events are discarded instead of being
  queued, and reported through your `LogDeliveryListener` (see below) if one is configured, so this data
  loss is never silent. Raise this limit if your traffic legitimately bursts past the default.
- **Concurrent transmissions:** up to `MaxConcurrentTransmissions` requests (4 by default) can be
  transmitted at the same time (see step 4 above), each on its own virtual thread, so network latency does
  not throttle how fast new batches are formed. This only ever adds backpressure to the internal
  background thread (never to the application thread): once that many transmissions are in flight, forming
  the next batch briefly waits for one of them to finish.
- **Chunked requests:** a batch bigger than `MaxRequestDocuments` (unbounded by default) is split into several
  requests instead of one, so a downstream system with its own per-request limit is never handed more than
  it can accept in a single call to `sendBulk()`. Chunks of the same batch may transmit concurrently, and
  each retries on its own failure without affecting or duplicating its siblings.
- **Shutdown:** when the appender is stopped, it stops accepting new logs, waits (up to the given
  timeout) for already-accepted logs to be processed, and only then flushes and closes the delivery
  handler, waiting for that final transmission to complete as well, so that logs emitted just before
  shutdown are not lost. This requires a non-zero shutdown timeout to actually wait: set the
  `shutdownTimeout` attribute (in milliseconds) on the root `<Configuration>` element of your Log4j2
  configuration, as it otherwise defaults to not waiting at all.
- **Failures:** if `sendBulk` returns `false` (or throws), the batch is kept and retried later instead of
  being lost, up to the safety limits described above. Any exception thrown while converting or sending a
  log is reported through Log4j2's `StatusLogger` (visible with `-Dlog4j2.debug` or a `<StatusListener>`)
  instead of being silently swallowed, in addition to being reported to your `LogDeliveryListener` if one
  is configured.

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
