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

1. Log4j2 calls `append(LogEvent)` on your appender (application thread).
2. The event is snapshotted immediately and submitted to a single background thread; `append()` returns
   right away, never waiting on that task.
3. The background thread converts the event (using your `LogConverter`) and hands it to your `LogDelivery`,
   which stacks it into the current batch.
4. Once `FlushThreshold` documents accumulate, or `FlushInterval` elapses since the last transmission
   (whichever comes first), the batch is due: if bigger than `MaxRequestDocuments`, it is first split into
   chunks of that size (unbounded, i.e. no splitting, by default). Each chunk is handed to its own virtual
   thread, and the background thread immediately resumes accumulating the next batch, never waiting on the
   network. Up to `MaxConcurrentTransmissions` requests (4 by default) run at the same time; beyond that,
   the background thread waits for a slot, so the downstream system is never overwhelmed.
5. A failed request's documents are put back in front of the batch and retried at the next opportunity
   instead of lost, without hammering the system on every incoming message in the meantime; a failing chunk
   retries independently of its siblings. Two safety nets bound how far this can go: the pending batch never
   grows past `MaxPendingDocuments` in memory (oldest documents discarded past that point), and a request
   failing `MaxConsecutiveFailures` times in a row is discarded instead of retried forever (which would
   otherwise happen if a single document can never be accepted by the downstream system). Both are reported
   through a `LogDeliveryListener` if configured (see [Observability](#observability) below), so this data
   loss is never silent.

Steps 2–3 (batching) always run on the same background thread, so your `LogDelivery` implementation never
needs to worry about concurrent calls to `send()`/`flush()`. Step 4 (transmission) may run concurrently
across virtual threads, so `sendBulk()` (see below) must be safe to call concurrently with itself if you
keep mutable state; retried batches may also be redelivered out of order after a failure, which is fine
since each document already carries its own timestamp.

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

To release resources (e.g. an HTTP client), override `close()`, calling `super.close()` first so the
current batch is flushed before they're released.

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
| `stacktrace`  | Stack trace, only present with an exception, truncated to 8'192 characters by default (see `getMaxStackTraceLength()`) so a deep or recursive cause chain can't inflate a document on its own |

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

All three have a no-op default; override only what you need. `onDocumentsDropped` deserves the most
attention in production — it fires only when documents are irrecoverably discarded (see the safety nets
above), which should normally never happen and is worth alerting on. It receives the actual documents (an
unmodifiable list), not just a count, as a last chance to save them.

## Configuration reference

These settings are defined by `LogConfiguration` and used regardless of the delivery mechanism (each
appender implementation typically exposes them as its own plugin attributes, see its documentation):

| Setting         | Description                                                                                              |
|-----------------|-------------------------------------------------------------------------------------------------------------|
| `Application`   | Name of the application generating the logs, included in every document.                                    |
| `Host`          | Name of the host the application is running on, included in every document.                                 |
| `Environment`   | Name of the environment the application is running in, included in every document.                         |
| `FlushThreshold`| Number of accumulated documents that triggers a transmission. `1` sends every log individually (no batching). |
| `FlushInterval` | Maximum time between two transmissions, so a partial batch isn't delayed indefinitely under low traffic. Only relevant when `FlushThreshold` is above `1`. |
| `MaxConcurrentTransmissions` | Maximum number of requests transmitted at the same time (see step 4 above). Defaults to `4`; must be at least `1`, or every transmission would wait forever. |
| `MaxPendingDocuments` | Maximum documents a pending batch can hold in memory; oldest discarded past that (see step 5 above). Independent of `MaxRequestDocuments`. Defaults to `10'000`; `0` or less discards every document immediately, disabling this delivery. |
| `MaxRequestDocuments` | Maximum documents sent together in a single request; a bigger batch is split into chunks of that size (see step 4 above), each retried independently. Defaults to unbounded (no chunking); set to match a downstream system's per-request limit. |
| `MaxConsecutiveFailures` | Maximum consecutive failed attempts for the same request before it is discarded instead of retried again (see step 5 above). Defaults to `5`; `0` or less retries indefinitely. |
| `MaxQueuedEvents` | Maximum logging events queued awaiting conversion and batching by the background thread; oldest discarded past that to make room for a new one, mirroring `MaxPendingDocuments`. Defaults to `10'000`. |

These five are default methods on `LogConfiguration` itself (rather than plugin attributes), so they're
available even if the appender implementation you use doesn't expose them as configuration; override them
in your own configuration class if needed.

## Shutdown and failure handling

- **Shutdown:** stopping the appender stops accepting new logs, waits (up to the given timeout) for
  already-accepted ones to be processed, then flushes and closes the delivery handler, waiting for that
  final transmission too, so logs emitted just before shutdown aren't lost. This needs a non-zero shutdown
  timeout to actually wait: set the `shutdownTimeout` attribute (in milliseconds) on the root
  `<Configuration>` element of your Log4j2 configuration, as it otherwise defaults to not waiting at all.
- **Failures:** if `sendBulk` returns `false` (or throws), the batch is kept and retried later instead of
  lost, up to the safety limits above. Any exception thrown while converting or sending a log is reported
  through Log4j2's `StatusLogger` (visible with `-Dlog4j2.debug` or a `<StatusListener>`) instead of being
  silently swallowed, in addition to your `LogDeliveryListener` if configured.

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
