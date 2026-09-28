/**
 * Building blocks of Log4j2 appenders shipping logs to an external system by batches, off the application
 * thread, with bounded memory, retries and a bounded shutdown.
 * <ul>
 *     <li>{@link com.chavaillaz.appender}: the batching engine and the contracts of a delivery, with its
 *     options, listeners and metrics</li>
 *     <li>{@link com.chavaillaz.appender.log4j}: the appender, the batching delivery and the conversion an
 *     appender extends</li>
 * </ul>
 * The Log4j2 and JSpecify APIs are required transitively, the API of this module exposing their types. An
 * appender built on it is a plugin Log4j2 creates by reflection, so the module declaring it opens its package to
 * {@code org.apache.logging.log4j.core}.
 */
module com.chavaillaz.appender {

    requires transitive org.apache.logging.log4j;
    requires transitive org.apache.logging.log4j.core;
    requires transitive org.jspecify;

    exports com.chavaillaz.appender;
    exports com.chavaillaz.appender.log4j;

}
