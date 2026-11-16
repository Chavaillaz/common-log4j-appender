# Scenarios

What can go wrong between an application logging an event and the downstream system receiving it, and what comes
of it, in two parts:

- [Validated](#validated): the scenarios the library handles, each checked by a test, and those that cannot
  happen.
- [Left as is](#left-as-is): the scenarios that can happen and stay so, documented, left as a known limitation,
  or minor.

The tested scenarios are the names of the tests (their `@DisplayName`), grouped by the class and the `@Nested`
group holding them: a test added, renamed or removed changes this list too.

## Validated

### Covered by a test

#### Appender

`AbstractLogDeliveryAppenderTest`

**Snapshot of an event on the logging thread**

- Formats a message with its arguments as they were when logged, not when processed
- Still hands off an event whose message fails to format on the calling thread
- Records the name, id and priority of the thread that logged an event, even one resolving them lazily
- Leaves out the caller location, found by walking the stack, for the appenders after it too
- Keeps the caller location for an appender requiring it

**Logging without blocking**

- Returns immediately even while a delivery task is still running

**Bounded queue of the worker**

- Discards an event without blocking once the queue capacity is exceeded, reporting it as dropped
- Keeps reporting discards once the listener threw an Error on one of them
- Discards the oldest queued event to make room for the newest instead of rejecting it
- Claims the slot freed by an eviction before notifying the listener about it
- Discards the newest event when another one takes the slot its eviction freed
- Skips converting an event discarded while nothing listens for discards
- Reports a discard on another thread than the one logging, however slow the listener
- Logs a discard instead of waiting once as many as the queue holds wait for the listener
- Reports discards waiting together apart when their reasons differ, each under its own
- Reports discards on a thread that does not take after the first thread to have one
- Reports an event the worker shut down under while it was being queued, rather than stranding it

**Periodic flush**

- Schedules a periodic flush and waits for it when the flush threshold allows batching
- Builds a single delivery handler and scheduler however many times it is started
- Finishes starting when the delivery options cannot be read at all
- Does not flush before the configured interval elapses
- Keeps flushing periodically after one flush failed to be queued with an Error
- Runs a periodic flush the full queue had no room for right after the event in progress
- Keeps a single periodic flush in a queue backlogged for many periods
- Keeps flushing periodically once an event evicted the queued flush from a full queue
- Keeps flushing periodically once a full queue refused the flush of a period
- Runs a periodic flush the full queue had no room for even while the options cannot be read
- Retries a failed document without batching nor backoff, before any other event comes
- Retries a failed document once its retry backoff elapsed, before any other event comes

**Flush on a severe event**

- Triggers an immediate flush right after an event at least as severe as FlushLevel
- Triggers an immediate flush right after an event exactly at FlushLevel
- Flushes right after an urgent event even when the queue has no room for anything else
- Does not flush for an event less severe than FlushLevel
- Never flushes on severity alone while FlushLevel is left disabled

**Conversion task**

- Converts each event with a DefaultLogConverter and sends the document to the delivery
- Converts each event with the converter the appender returns instead, when it overrides it
- Reports an event the converter fails on as dropped, since it is never sent
- Reports an event its delivery throws on as dropped, since the delivery did not keep it

**Failing tasks**

- Reports a failing task and keeps processing the next ones
- Reports an exception thrown while creating a task without propagating it to the caller
- Reports an event whose task comes back null as dropped, as a task failing to be created
- Never reports an event as dropped once it has already been handed to the worker
- Logs nothing of its own failures back into the appender, whatever fails while it delivers an event

**Delivery failing to be created**

- Reports the failure once and skips processing further events instead of failing on every one
- Reports a delivery handler that comes back null instead of discarding every event in silence
- Reports an Error from the handler's creation instead of failing the whole configuration

**Health, metrics and backpressure**

- getHealth() is down until started, the one of its delivery while started, and down once stopped
- getHealth() is down from the moment the appender starts stopping
- Adds what its queue holds and discarded to the metrics of its delivery
- isBackpressured() reflects whether the internal queue is currently full
- getQueuedEventCount() reports how full the internal queue is before it overflows
- getHealth() and getMetrics() take the defaults of a delivery tracking neither

**Stop**

- Waits for pending tasks to complete before closing the delivery handler
- Closes a delivery implementing only close() when stopped, through the default close(Duration)
- Waits for pending tasks when Log4j2 leaves the timeout to the appender
- Closes the delivery within what processing the queued events left of the stop budget
- Bounds the wait by CloseTimeout when Log4j2 leaves the timeout to the appender
- Waits without bound when Log4j2 leaves the timeout to an appender whose CloseTimeout is unset
- Falls back to the close timeout it was built with when the current one cannot be read
- Never keeps the JVM alive on its own once the application is done
- Names its threads after the appender, telling two appenders apart in a thread dump
- Ends its worker and its flush scheduler once stopped, leaving no thread behind
- Never keeps the context of the first thread to log for as long as it runs
- Never keeps the context of the thread starting it, which creates its worker and its scheduler
- Runs the converter, the transport and the listener with the class loader of the appender, whatever thread starts it
- Finishes stopping even when closing the delivery fails with an Error
- Rejects further appends without throwing back to the calling thread
- Reports an event appended once stopped as rejected, without formatting its message
- Never fails the application thread when the listener throws an Error on an event appended once stopped
- Attempts to interrupt a still-running task once the shutdown timeout elapses
- Reports events still queued behind a stuck task as dropped instead of losing them silently
- Still reports the events abandoned along with one that fails to convert
- Interrupts a still-running task and reports the queued events when the stop is interrupted
- Reports the discards still waiting for a slow listener before it returns, once its budget is spent
- Never reports the listener as still notified once its budget is spent, when it returned already
- Reports the batch its worker still waited to send before a stop whose budget is spent returns

#### Conversion

`DefaultLogConverterTest`, `LogConverterTest`

**Default converter**

- Converts every field of a fully populated logging event
- Writes the instant of an event with three digits of milliseconds, whatever their value
- Does not include exception fields when the event carries no exception
- Converts an event carrying no MDC context data at all, without a warning
- Does not let an MDC entry override a reserved field
- Does not let an MDC entry fill a reserved field left null
- Prefers the logger name over the internal fully qualified caller class name
- Falls back to the class of the logger when the logger name is missing
- Converts a null message to a null logmessage field
- Cannot be built without a configuration, rather than converting without it
- Truncates the message once it exceeds the configured maximum length
- Truncates a message at 32'768 characters by default
- Cuts before a surrogate pair rather than splitting one at the limit
- Does not truncate a message within the configured maximum length
- Does not truncate a message exactly at the configured maximum length
- Disables message truncation entirely when the configured maximum length is zero or negative
- Truncates the stack trace once it exceeds the configured maximum length
- Truncates a stack trace at 8'192 characters by default
- Cuts a stack trace before a surrogate pair too, rather than splitting one at the limit
- Stops printing a stack trace once past the configured maximum length rather than printing it all
- Does not truncate a stack trace within the configured maximum length
- Does not truncate a stack trace exactly at the configured maximum length
- Disables truncation entirely when the configured maximum length is zero or negative
- Falls back to a placeholder instead of losing the whole document when a message fails to format
- Names the failure of a message by its class when the failure cannot describe itself either
- Falls back to omitting the field instead of losing the whole document when a stack trace fails to compute
- Keeps the rest of the document when the configuration cannot answer for its own fields
- Keeps the rest of the document when a field's own code fails with an Error rather than an exception
- Names an anonymous exception class by its binary name, which it has, rather than not at all
- Falls back to omitting MDC fields instead of losing the whole document when context data fails to be read
- Converts an event whose context data claims more entries than a document can hold

**Converter named in a configuration**

- Creates the converter named through its constructor taking the configuration
- Takes the constructor declaring the most specific type of the configuration
- Refuses to choose between constructors taking unrelated types of the configuration
- Creates the default converter by its name too
- Refuses a converter without a constructor taking the configuration
- Reports the failure of the constructor of the converter
- Refuses a class that is not a converter, or cannot be found

#### Delivery

`AbstractBatchLogDeliveryTest`

**Below the flush threshold**

- Does not trigger a transmission
- Flushing an empty batch does not trigger a transmission
- Sending null or no documents does not trigger a transmission
- Sending a null document does not trigger a transmission nor stack a null entry
- Filters out null elements of a list before stacking them
- Does not trigger a transmission when a list only contains null elements

**Once the flush threshold is reached**

- Triggers a single transmission of the accumulated batch
- Is also reached when several documents are sent in a single call
- Can also be forced early by calling flush()
- Follows a flush threshold the configuration changes once the delivery is built

**Failed transmissions**

- Keeps the documents and retries on the next flush
- Is not retried on every new message while batching is enabled, only on the next flush
- Is retried on every message when batching is disabled (flush threshold of 1)
- Holds off retrying for the configured RetryBackoff, until closed
- Retries at the next flush while RetryBackoff is zero, whatever MaxRetryBackoff
- Draws its retry delays under RetryBackoff at first, and under MaxRetryBackoff as failures follow

**Safety limits**

- Discards the oldest documents once a retried batch exceeds the maximum pending documents
- Sends a batch filling up to the maximum pending documents below the flush threshold
- Discards the oldest documents while accumulating as transmissions are held off, before any retry
- Gives up on a batch after too many consecutive failed attempts, unblocking the pipeline
- Keeps retrying when MaxConsecutiveFailures is the largest value it takes
- Gives up on a poison document based only on its own failures, unaffected by unrelated concurrent successes

**Request and pending size limits**

- Splits a batch into requests that each stay under MaxRequestBytes
- Sends a batch reaching MaxPendingBytes below the flush threshold, rather than discarding from it
- Sends a document heavier than MaxPendingBytes on its own, rather than discarding it
- Discards the oldest documents once the pending batch exceeds MaxPendingBytes while held off
- Enforces a pending size limit a subclass supplies by overriding getMaxPendingBytes()
- Never measures a document while both size limits are left at their defaults

**Requests split by count**

- Sends the whole batch as a single request by default (no chunking)
- Splits a batch larger than MaxRequestDocuments into several requests, none exceeding it
- Transmits chunks of the same batch concurrently rather than sequentially
- Retries a failed chunk independently, without affecting or duplicating a sibling that succeeded
- Retries only the rejected documents of a PartialFailure, reporting the accepted ones as succeeded

**Close**

- Flushes and waits for the remaining batch to be transmitted before returning
- Reports the documents still pending once closed instead of discarding them silently
- Closes within the timeout it was built with, and reports what was pending, when the current one cannot be read
- Gives up after the configured close timeout instead of waiting forever on a stuck transmission
- Gives up after the timeout it is closed with, however long the configured one
- Closes through close(Duration) with the configured timeout, so an override releasing resources runs
- Closes with the close timeout configured by then, rather than the one it was built with

**Health and metrics**

- Reports up before any transmission, retrying after a failure, up once retried, and down once closed
- Reports retrying once a request was not answered within TransmissionTimeout, its documents kept
- Reports its pending documents, their estimated size under a byte limit, and what it sent

**Listener**

- Notifies the listener when a transmission succeeds
- Notifies the listener installed after construction rather than the one it replaced
- Notifies the listener with no cause when sendBulk() reports a failure without one
- Notifies the listener with the cause when sendBulk() throws
- Still requeues failed documents for retry even when onTransmissionFailed() throws
- Keeps transmitting further batches even when onTransmissionSucceeded() throws

**Concurrency**

- Does not lose or duplicate documents when sent concurrently by several threads
- Overlaps transmissions instead of delaying the accumulation of the next batch
- Bounds the number of transmissions running at the same time
- Can be lowered through the configuration
- Does not block accumulation of the next batch while notifying a slow listener of dropped documents
- Does not block the transmitting thread when sendBulk() completes its future asynchronously

#### Batching engine

`BatcherTest`, `BatcherFuzzTest`, `PendingBatchTest`, `ChunkTest`, `BoundedTrimTest`, `DynamicLimitsTest`,
`BackoffPolicyTest`, `DurationsTest`, `ThreadFactoriesTest`

**Construction**

- Rejects a non-positive maximum number of concurrent transmissions at construction
- Rejects a missing transport function at construction

**Accumulation**

- Rejects a null element before accumulating any of the elements given with it
- Accumulates elements of an arbitrary type and transmits once the threshold is reached
- Re-reads its dynamic limits on every call instead of capturing them once at construction
- Never hands the transport an empty request, however low the flush threshold

**Failed transmissions**

- Retries the batch on the next flush when a transmission fails
- Reports dropped elements through the listener once MaxConsecutiveFailures is exceeded
- Holds off retrying while the backoff policy says to wait, then retries once it elapses
- Ends the backoff of a failure once a transmission still in flight succeeds
- Keeps the longest backoff when a later failure asks for a shorter one
- Asks the backoff policy for a delay matching the consecutive failures of its elements
- Retries the elements of a transport returning no future, as a failure without cause
- Retries the elements of a future completed without result, as a failure without cause
- Reports the cause of a failure a future derived by the transport wraps, rather than its wrapper
- Never charges a failure streak to elements the requeue that carried it kept none of
- Counts the failures of an element joining a failed one from its own first attempt
- Keeps the failure streak of each element across the requests a batch is split into

**Unanswered transmissions**

- Fails a transmission not answered within its timeout, and retries its elements
- Interrupts a transport still blocked past the timeout, freeing its slot
- Cancels the future of a transmission that timed out
- Ignores the answer of a transmission coming after its timeout
- Leaves a transmission answered in time alone, neither failed nor cancelled once its timeout elapses
- Still ends what a transport does for a transmission a close gave up on, without handling it again
- Waits for the answer for as long as it takes without a timeout, or with one that is not positive

**Partly failed transmissions**

- Retries only the rejected elements of a PartialFailure, reporting the rest as succeeded
- Treats a PartialFailure rejecting none of its elements as a success
- Counts no success for a PartialFailure rejecting every one of its elements
- Treats a PartialFailure reporting more rejected elements than were dispatched as a failure instead of losing them silently
- Never requeues an element a PartialFailure referenced but the chunk never contained
- Warns of a rejected element the request never held, such as an equal copy of one it did
- Does not warn of a request holding an element twice when the transport rejects it once
- Never loses an element to a malformed PartialFailure, across many randomized trials

**Request and pending size limits**

- Splits a batch into chunks that each stay under the maximum request size
- Fills a chunk up to the maximum request size exactly, cutting only past it
- Sends an element larger than the maximum request size on its own rather than never at all
- Sends elements measured at the largest size apart rather than adding them up past overflow
- Measures each element once when splitting a batch, whatever the number of chunks
- Sends a batch weighing the maximum pending size exactly, as it sends one past it
- Discards the oldest elements once the pending batch weighs more than allowed while held off
- Discards the oldest elements a failed request puts back past the maximum pending size
- Never measures an element when no measurement was supplied at all

**Close**

- Reports the elements still pending when closed as dropped instead of discarding them silently
- Gives up closing once the timeout elapses instead of waiting forever on a stuck transport
- Still transmits the final flush when closed with no time to wait, and reports it, nothing waiting for it
- Releases a caller blocked waiting for a permit once a bounded close begins
- Reports the requests another thread still waits to transmit before the close returns, oldest first
- Leaves to the close the requests an interrupted thread could not transmit once it drained the batch
- Reports the requests a close found no permit for in their order, oldest first
- Reports the elements of a transmission still in flight once closing gives up on it, and only once
- Returns at once when closed again, rather than waiting again for a transmission it gave up on
- Waits for the close in progress when closed concurrently, rather than returning before it is over
- Logs an interrupted wait for the close in progress, as the close itself logs one
- Reports elements a failed transmission hands back after closing drained the batch
- Reports elements submitted after being closed as dropped instead of stacking them for nobody
- Waits for the listener still notified of a dropped element before returning from close
- Gives up waiting for a listener stuck on a dropped element once the close timeout elapses
- Ignores an active backoff while closing, that transmission being the last chance to send at all

**Faulty collaborators**

- Counts a future that cannot be listened to as a failure, releasing its slot
- Keeps the elements of a failed request whose transport tried to empty the list it was given
- Treats a measurement that throws as weightless instead of failing the pipeline
- Retries a chunk whose transport threw an Error instead of leaking its transmission permit
- Retries a chunk whose thread failed to start with an Error instead of leaking its permit
- Notifies a discard on the calling thread when its own thread failed to start with an Error
- Still requeues a failed chunk when the listener notified of that failure throws an Error
- Still requeues a failed chunk when the backoff policy consulted for it throws an Error
- Treats a measurement throwing an Error as weightless instead of propagating to the caller
- Keeps a batch whose request limit could not be read, instead of losing it on the way out
- Keeps a failed batch whose pending limit could not be read while putting it back

**Threads**

- Transmits without the context of the thread handing the batch over
- Calls the transport and the listener with the context class loader it was built with
- Cancels the future of a transmission that timed out with that context class loader too
- Does not block the calling thread on a transport that completes its future asynchronously
- Never holds up the client thread completing a transmission with the handling of its result
- Retries the requests an interrupted dispatch put back in their order, oldest first
- Bounds how many drop notifications a slow listener can have in flight at once
- Leaves its carrier to other virtual threads while one waits for the pending batch

**Health, metrics and diagnostics**

- Reports itself retrying after a failed transmission, up once one succeeds, and down once closed
- Counts the time since its last success from a request accepted in part too
- Tells the listener how long an accepted transmission took
- Reports itself backing off while a failure holds transmissions off, whatever succeeded meanwhile
- Reports its own diagnostics on Log4j2's status channel, not through an application logger
- Takes a snapshot of what it holds right now, and of what it did so far
- Logs the cause of every failure the transport returns, before any element is given up on
- Logs the cause of the last failure along with the elements it gives up on

**Pending batch**

- Puts what failed before every newer element, with the streaks of its elements
- Keeps the streaks of the elements behind a chunk whose runs leave out its last elements
- Forgets the streaks of the elements a trim discards
- Splits within the request limits, each chunk carrying the streaks of its own elements
- Hands each chunk over as an unmodifiable list of its own
- Discards the oldest elements until the batch fits within the size given
- Never counts a negative size once an element weighs more leaving than it did coming in
- Tracks no size and never trims by size without a measurement
- Takes every element out for good when drained
- Slices runs past their end as elements that never failed
- Reads no streak for an element past the last run

**Requests and their failure streaks**

- Matches the rejected elements by reference, an equal copy rejecting none of them
- Rejects an element as many times as the chunk holds it, however many times it is reported
- Hands the rejected elements over with the failure streaks they had
- Accepts every element when none is rejected
- Counts one more failure for each element, keeping it for a retry below the limit
- Gives up on the elements reaching the maximum number of consecutive failures
- Never gives up on an element while the maximum is not positive
- Reports no streak once every element is given up on

**Trimming to a cap**

- Keeps everything and discards nothing when under the cap
- Keeps everything and discards nothing when exactly at the cap
- Discards the single oldest element when one over the cap
- Discards every oldest element in excess, keeping the most recently added ones
- Discards everything when the cap is zero
- Treats a negative cap the same as zero, discarding everything, instead of throwing
- Does not discard anything, or throw, for an empty queue
- Does not throw when the cap is larger than the queue itself
- Hands back the discarded elements as an unmodifiable list

**Limits read at runtime**

- Reads each limit from its own supplier, again on every use
- Leaves a limit whose supplier throws unenforced rather than discarding over it
- Leaves a limit unenforced when its supplier throws an Error

**Retry backoff**

- Never delays with the no-op policy
- Falls back to never delaying when the initial delay is not positive
- Grows the delay ceiling exponentially, capped at the maximum
- Keeps backing off at the cap for a failure count large enough to overflow the exponential
- Keeps backing off at the cap when doubling a base delay of months overflows
- Keeps backing off at the cap across every consecutive failure count in a long outage
- Tolerates durations too large to be expressed in milliseconds
- Disables backoff for an initial delay too far below zero to be expressed in milliseconds
- Falls back to never delaying when the initial delay is null
- Treats a null maximum as no cap beyond the initial delay
- Treats a maximum smaller than the initial delay as the initial delay
- Clamps a failure count below one to the first attempt

**Durations**

- Converts a duration within a year to as many milliseconds
- Clamps a duration past a year, or below zero, to the nearest of both
- Clamps a duration too long to count in milliseconds rather than throwing

**Thread factories**

- Gives the threads of a factory the context class loader asked for
- Leaves the threads of a factory as they are without a context class loader
- Hands over a thread its factory failed to create as it is

#### Listeners and dead letters

`LogDeliveryListenerTest`, `FileDeadLetterListenerTest`

**Composed listeners**

- Composes listeners into one notifying each of them in turn, of every outcome
- Notifies the next listeners when one fails, then throws the first failure
- Throws an Error of a listener as it is, once the next ones were notified
- Wraps a checked exception a listener sneaks past the compiler, once the next ones were notified
- Composes no listener into the no-op one, and a single listener into itself
- Rejects a null listener when composing rather than when notifying

**Dead-letter file**

- Appends one JSON line per dropped document, wrapping it with the reason and a timestamp
- Moves on to the file of the next day at midnight
- Starts a day at midnight in the time zone of its clock
- Appends the date to a file name with no extension, a leading dot not starting one
- Appends across multiple calls instead of overwriting
- Ends a line a write cut short left incomplete rather than appending to it
- Appends on an interrupted thread, as an interrupted stop reports what it abandons
- Ends an incomplete line before appending to it on an interrupted thread too
- Starts an existing file left empty with its first line, without ending one that is not there
- Keeps each line whole while another listener appends to the same file
- Writes one line per document when multiple are dropped together
- Escapes characters JSON requires escaping in string values
- Escapes control characters too, which JSON does not allow raw in a string
- Saves half of a surrogate pair as its JSON escape rather than losing it
- Serializes nested maps, lists, numbers, booleans and null correctly
- Writes an array as a JSON array rather than as its identity hash
- Keeps a number JSON cannot express as a string instead of writing an unreadable line
- Keeps a custom Number rendering as something other than a JSON number as a string
- Writes the numbers JSON does accept as bare numbers
- Truncates a self-referential document instead of overflowing the stack
- Truncates a document nested deeper than the limit instead of overflowing the stack
- Writes nesting up to the depth limit in full
- Creates the parent directory of the target file if it does not exist yet
- Fails at construction when the parent directory cannot be created, rather than at the first drop
- Creates the parent directory again once removed, as a cleaner of old files does
- Saves the rest of a document when one of its values cannot be rendered
- Replaces a collection failing partway through its iteration as a whole, keeping the line valid
- Reports a write failure through onWriteFailure instead of throwing
- Logs the documents it could not save by default, so that they appear somewhere
- Hands over only the documents a write failing partway left out of the file
- Hands over every document when only closing the file fails, which can mean none reached it
- Renders the documents write by write rather than all of them before the first write
- Does not create the file at all when there is nothing to append
- Leaves other virtual threads a carrier while writers wait for their turn at the file

#### Size estimate, options, metrics, health and results

`DocumentSizeEstimatorTest`, `DeliveryOptionsTest`, `DeliveryMetricsTest`, `DeliveryHealthTest`,
`TransmissionResultTest`

**Size estimate**

- Estimates nothing for a null or empty document
- Grows with the content of the document
- Counts every separator of the compact JSON rendering once
- Counts null and booleans as the JSON literals they are written as
- Counts a string by its UTF-8 byte length, not its character count
- Follows nested maps and lists rather than counting them as one opaque value
- Counts an array by its elements rather than as one opaque value
- Terminates on a document that contains itself instead of recursing forever

**Delivery options**

- Defaults to the values documented as the pipeline's out-of-the-box behavior
- A configuration that overrides nothing gets exactly those defaults
- Derives a new instance overriding one setting and carrying the rest over
- Equals options built with the same settings, with the same hash code
- Differs from options a single setting tells apart
- Compares every setting it has, none being left out of the comparison
- Lists every setting with its value when printed
- Rejects a setting out of its range when built, naming it
- Rejects a missing setting when built, naming it
- Accepts every setting at the edge of its range

**Delivery metrics**

- Starts every value at zero, as the metrics of a pipeline tracking none
- Keeps each value under the name it was set by
- Derives metrics replacing some values and carrying the others over
- Equals metrics with the same values, with the same hash code
- Differs from metrics a single value tells apart
- Compares every value it has, none being left out of the comparison
- Lists every value with its name when printed

**Delivery health**

- Keeps the status and the time since the last success it is created with
- Cannot be created without a status or a time since the last success, naming what is missing
- Equals a health with the same status and time, and none differing by either
- Gives its status and the time since the last success when printed

**Transmission results**

- Keeps a partial failure as it was returned, however it was built

#### Host name and properties

`CommonUtilsTest`

- Falls back to the given default value when the key is not found
- Returns the system property value when the key is found
- Returns the environment variable of that name before the system property
- Returns the canonical host name of the local machine
- Falls back to localhost when the local machine has no name that resolves
- Gives the host name its lookup finds, and localhost to a call made before it found it
- Looks the host name up once, every later call sharing its result
- Returns the lookup's result when it completes before the timeout
- Falls back to localhost instead of waiting when the lookup exceeds the timeout
- Hands a later call the result of a lookup that outlived the wait of an earlier one
- Falls back to localhost when the lookup itself throws

#### Under load, and through Log4j2

`LoadTest`, `PluginIntegrationTest`

**Delivery under load**

- Delivers or reports every document while the downstream system times out
- Reports every document a downstream system never answering holds once closed

**Appender under load**

- Delivers or reports every event logged by concurrent threads while the downstream system times out
- Reports every event a downstream system never answering holds once stopped
- Counts every event it discards as dropped, those discarded faster than it reports them included

**Plugin loaded by Log4j2**

- Discovers the appender by its plugin name, binds attributes to its builder, and delivers a logged message through it
- Delivers every message logged right before the context stops with no shutdownTimeout set

### Ruled out

The scenarios that cannot happen, and why.

- **The snapshot of an event failing the logging thread.** `toImmutable()` only copies an event, Log4j2 formatting
  the message of a reusable one while it builds it, before any appender. Whatever `append()` would throw, the
  `AppenderControl` of Log4j2 catches anyway.
- **The status output of the library coming back to the appender through a redirected `System.err`.** The status
  logger writes to the `System.err` it found when it was initialized, before any redirection set up through
  Log4j2 can exist.
- **A listener calling back into its delivery deadlocking.** The engine releases its lock before every
  notification.
- **A stale interrupt of the worker wedging it.** `ThreadPoolExecutor` clears it before each task.
- **A stop confusing two equal events while it removes one from the queue.** `remove()` matches with `equals()`,
  and two equal events are interchangeable: either one is reported.
- **Two requests carrying equal documents confused while in flight.** A request is compared by identity.
- **The backoff deadline overflowing.** A delay counts a year at most, and `System.nanoTime()` readings are only
  compared through their difference, exact for any delay that fits a `long`.
- **The metric of transmissions in flight counting permits a close took in part.** `Semaphore.tryAcquire(n)` takes
  all of them at once or none.
- **A hanging lookup of the host name keeping a carrier thread.** The lookups of `InetAddress` let the scheduler of
  virtual threads add a carrier meanwhile.
- **A dead-letter escape written with localized digits.** Hexadecimal formatting is never localized, unlike
  decimal (checked under `ar-SA`, `th-TH`, `hi-IN` and `fa-IR`).
- **The pattern of a JSON number backtracking on a huge rendering.** It has no nested quantifier, and matches in
  linear time.
- **The exception stopping a stack trace past its maximum length carrying state between threads.** Its single
  shared instance has neither state nor stack trace.

## Left as is

### Documented

What an application or an implementer has to know, which the README or the Javadoc states.

- **Code inside the pipeline logging through an ordinary logger feeds the appender.** A converter logging each
  event, or a client library logging each request, starts a loop from a single event: with the root logger at
  `DEBUG` and `FlushThreshold` 1, 299,610 requests in 2 seconds through such a converter, 63,675 through such a
  client. Converters, deliveries and listeners log through `StatusLogger.getLogger()`, and a configuration keeps
  the loggers of the client away from the appender.
- **A blocking client waiting inside `synchronized` code pins its carrier thread** on Java 21 to 23. Four such
  requests kept an unrelated virtual thread from running for 3.9 seconds with two processors.
- **A stop killed by the platform before its report.** What is still pending is reported once the stop budget is
  spent: a budget longer than what the platform grants a stopping process (10 seconds for `docker stop`, 30 for
  Kubernetes) reports nothing during an outage. The default `CloseTimeout` of 5 seconds stays under both for one
  appender, but Log4j2 stops appenders one after the other, each with the whole budget: two appenders whose
  requests are never answered took 10,044 ms to stop, the second reporting at 10,043 ms.
- **A request never answered fails once `TransmissionTimeout` elapses,** one minute by default: its slot is freed
  and its documents are retried, delivered twice if the downstream system accepted them all the same. With that
  timeout unset and none in the client, it keeps its slot until the delivery closes, and once such requests hold
  them all, the pipeline stalls, its queue discarding the oldest events.
- **A transport ignoring interruption keeps its thread past the timeout.** The slot is freed and the documents are
  retried, but each attempt leaves one more virtual thread waiting in the client, with the documents it was given.
- **A `Failover` appender never falls back from this one,** which never fails a call to `append()`. A
  `FileDeadLetterListener` is the fallback.
- **The health of the delivery gating the readiness of the application.** An outage of the downstream system then
  takes every instance out of service at once, and an instance out of service logs nothing that could bring it
  back. The health and the backpressure signal are meant for health indicators and alerts.
- **Delivery is at least once.** A request the downstream system processed although its answer failed is retried,
  and its documents delivered twice.
- **Retried documents are delivered out of order,** each carrying its timestamp.
- **A transmission still in flight once closed** is reported as dropped, and may be delivered all the same.
- **A converter reusing one document for every event.** The events `a`, `b` and `c` went out as `[c, c, c]`, and a
  partial failure rejecting one of them dropped all three. A converter returns a new document for every event.
- **Events the appender discards take the shape of `DefaultLogConverter`,** whatever converter the
  appender uses: a dead-letter sink then holds documents of two shapes.
- **Discards outpacing their reporter are only counted and logged.** At most `MaxQueuedEvents` of them wait to be
  reported: a burst of 50,000 events in 50 ms left up to 36,000 of them unreported, though counted, with a
  listener returning at once.
- **A stop waits for the last notifications of the listener,** whatever its budget.
- **An event appended once the appender stopped** is reported on the thread logging it.
- **A stop whose budget is spent, or that is interrupted, reports the queued events instead of sending them.** A
  thread already interrupted when it stops Log4j2 gives up waiting at once, as the `AsyncAppender` of Log4j2
  does.
- **A `null` `CloseTimeout` waits indefinitely,** safe only with a transport whose future always completes.
- **The caller location is left out** unless the appender overrides `requiresLocation()`.
- **Messages are formatted on the logging thread,** unless Log4j2 is told to format them in the background, or the
  message is `@AsynchronouslyFormattable`.
- **A document heavier than `MaxRequestBytes` is still sent,** alone.
- **A byte limit coming into effect at runtime** under-counts the documents already pending, and is enforced late.
- **Some settings are read once,** when the appender or its delivery is created: `FlushInterval`,
  `MaxConcurrentTransmissions`, `MaxQueuedEvents`, `RetryBackoff`, `MaxRetryBackoff` and `TransmissionTimeout`.
- **The time since the last success grows while there is nothing to send,** which is why it is read along with
  the status.
- **The host name falls back to `localhost`** when its lookup takes more than 5 seconds, a later call getting the
  real one.
- **Lines of a dead-letter file are kept whole only on a local file system.**
- **Documents a dead-letter file fails to save are logged on the status logger by default,** at error level, which
  Log4j2 prints to the standard error.

### Known limitations

The scenarios that can hurt, left for now, with what could be done about them.

- **The health stays `RETRYING` once every failed document was given up on,** until the next success: an
  instance logging nothing more keeps the alert of the README example firing, with nothing pending. Reading `UP`
  then would hide an outage that cost every document; a status of its own could tell both apart.
- **A request rejected whole for one document takes its neighbors with it.** With a downstream system refusing a
  whole request without naming the document at fault, one invalid document among 1,000 cost 159 valid ones, at
  `FlushThreshold` 100 and `MaxConsecutiveFailures` 5. Splitting a request failing whole in halves on retry would
  isolate it. Elasticsearch and OpenSearch name the documents at fault, and are not concerned.
- **Events logged by the threads of the appender come back to it.** Keeping out of the appender the events its
  worker and transmission threads log would stop the loops of converters, listeners and blocking clients in
  code, not those of a client logging on threads of its own.
- **Logging threads overloading the queue contend with the worker.** At `FlushThreshold` 1, throughput falls from
  570,000 to 250,000 events a second when four threads overload it, each eviction taking the lock the worker takes
  events under. A handoff without that lock, for many producers and a single consumer, would keep it.
- **Every appender built while the lookup of the host name hangs waits 5 seconds again.** The wait could be paid
  once.
- **Held off at its pending cap, a batch reports each document it evicts on its own:** a `FileDeadLetterListener`
  then opens its file once per event logged. Evictions could be reported in groups.
- **Status warnings are not throttled.** A fault hitting every document, a size estimate always throwing for
  instance, logs one warning per document, which `-Dlog4j2.debug` prints.
- **Dead-letter files have neither retention nor size cap.** An outage of days fills the disk, after which each
  failing write logs its documents on the status logger.
- **The size estimate and the dead-letter file copy a primitive array into a list of boxed values** to measure or
  write it, taking memory in proportion to the array. A view of the array would not.
- **An MDC value that is a mutable object is kept by reference:** a document sent, or retried, later carries its
  state at that time.
- **Dead-letter files follow symbolic links and take the default permissions of the process.** In a directory
  other users can write to, the predictable name of the file of the next day can be redirected: the directory
  should be private to the application.

### Minor

- **An event of a custom `LogEventFactory` whose `toImmutable()` returns itself** is queued as it is, not copied,
  so that the factory reusing it changes what is sent.
- **A subclass returning `0` from `getMaxPendingDocuments()`** discards everything while transmissions are held
  off. `DeliveryOptions` forbids `0`.
- **A stop called from a thread of the appender itself,** a converter or a listener stopping Log4j2, waits until
  its budget is spent before giving up on that thread.
- **A custom `LogDelivery` throwing from `getHealth()` or `getMetrics()`** throws from those of the appender too.
